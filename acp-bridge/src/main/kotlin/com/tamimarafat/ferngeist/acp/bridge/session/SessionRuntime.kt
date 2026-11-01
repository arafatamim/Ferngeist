package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.TurnTiming
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/**
 * A [SessionRuntime]'s state. All fields are immutable; the runtime's reducer produces new
 * instances. What of it survives process death is
 * [com.tamimarafat.ferngeist.acp.bridge.session.persist.SavedSessionState]'s decision.
 */
internal data class RuntimeData(
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val toolCallIndex: Map<String, ToolCallLocation> = emptyMap(),
    val usage: SessionUsage? = null,
    val availableCommands: List<CommandInfo> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val nativeConfigOptions: List<SessionConfigOption> = emptyList(),
    val legacyModes: LegacyModeState? = null,
    val legacyModel: LegacyModelState? = null,
    val title: String? = null,
    val pendingElicitations: List<ChatElicitationRequest> = emptyList(),
    /**
     * Id of the last message when the user stopped the turn, until the next prompt. What
     * the agent still sends goes below it, already closed: some agents (claude-agent-acp)
     * send no turn end after it, which would leave the chat looking busy for good.
     */
    val stoppedAt: String? = null,
    /**
     * The most recent turn's terminal outcome, and nothing else: only [TurnStatus.FINISHED]
     * and [TurnStatus.ERROR] are ever written. A new turn clears it, so a stale result is
     * never read as the current one.
     */
    val turnOutcome: TurnStatus = TurnStatus.UNKNOWN,
    /** Permission requests still awaiting the user, so a live turn can be blocked on them. */
    val pendingPermissions: Set<String> = emptySet(),
    /**
     * True once a terminal event settled the current turn. A later rollback of the
     * prompt awaiter must not overwrite that outcome (a cancellation stays cancelled),
     * while a send that failed without any terminal event still becomes an error.
     */
    val turnSettled: Boolean = false,
    val turnCompletionSequence: Long = 0,
    val turnStartedAt: ComparableTimeMark? = null,
    val turnFinishedAt: ComparableTimeMark? = null,
    /** The reply still streaming at the last publish, so its close can be stamped. */
    val openReplyId: String? = null,
    /** The gateway's measure of the turn that just ended, for [stampClosedReply]. */
    val turnDurationMs: Long? = null,
    /**
     * The gateway's update numbering this state follows: the agent process ([epoch]) and the
     * last update applied ([lastSeq]). Moved in the same step as the update, so a saved state
     * and its number always agree and a catch-up resume neither repeats nor skips one.
     */
    val epoch: String? = null,
    val lastSeq: Long = 0,
) {
    fun after(updateSeq: Long?): RuntimeData = if (updateSeq == null) this else copy(lastSeq = updateSeq)
}

/**
 * Owns canonical transcript/session state for one ACP session.
 *
 * SessionRuntime is the single source of truth for a session's UI state. All mutations
 * are serialized through [mutex] to ensure deterministic event ordering. It maintains
 * two copies of state:
 * - [live]: the currently visible state (published to [snapshot])
 * - [buffered]: state accumulated during hydration (session/load replay)
 *
 * Events from the SDK arrive via [onEvent] and are reduced using [SessionMessageReducer]
 * for message changes and a side-field [when] block for auxiliary state (usage, commands,
 * legacy modes, etc.). Config option changes use [SessionConfigPolicy] to centralize
 * compatibility logic between native and legacy options.
 *
 * The runtime held directly by [SessionBridge]; its mutating API stays internal to the
 * bridge and connection layers.
 */
class SessionRuntime(
    private val sessionId: String,
    /** Monotonic clock for segment arrival; null leaves live turns unstamped, as in tests. */
    private val clock: () -> Long? = { null },
    /** Wall clock for when a turn finished, shown to the reader as a time of day. */
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    // Volatile: written under [mutex], read whole by [state] without it, being immutable.
    @Volatile private var live = RuntimeData()
    private var buffered = RuntimeData()
    private var eventSeq = 0L

    /** Between [beginAttach] and its settle; a turn end arriving then is held in [heldTurnEnd]. */
    private var attaching = false
    private var heldTurnEnd: AppSessionEvent.TurnComplete? = null

    /** What the last attach learned about the gateway; see [SessionSnapshot.dropsRepeatPrompts]. */
    private var dropsRepeatPrompts: Boolean? = false

    /** The last attach left updates it could not refill; see [SessionSnapshot.historyGap]. */
    private var historyGap = false

    private val _snapshot =
        MutableStateFlow(
            SessionSnapshot(
                sessionId = sessionId,
                loadState = SessionLoadState.READY,
            ),
        )
    val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    suspend fun beginHydration() {
        mutex.withLock {
            debug { "beginHydration: clearing buffered/live snapshot view" }
            buffered = RuntimeData()
            // `usage` is deliberately NOT cleared here: it describes the context window of
            // this same session, and agents only emit `usage_update` at the end of a turn
            // (never during a `session/load` replay). Clearing it would blank the context
            // indicator until the next turn finishes.
            _snapshot.value =
                _snapshot.value.copy(
                    loadState = SessionLoadState.HYDRATING,
                    messages = emptyList(),
                    isStreaming = false,
                    availableCommands = emptyList(),
                    commandsAdvertised = false,
                    error = null,
                    // A load replay carries no outcome; claiming the previous one would
                    // paint a finished turn over a transcript that is being replaced.
                    turnStatus = TurnStatus.UNKNOWN,
                    turnStatusSince = null,
                )
        }
    }

    /**
     * Commits the replay. [turnRunning]: the gateway reports a turn still running behind it, so
     * the session stays busy until that turn's end arrives; the replay is folded here instead,
     * since the [AppSessionEvent.TurnEndUnheard] that would fold it also ends the turn. A turn end
     * held by the attach also keeps it open, so [settleAttach] closes (and times) its reply.
     */
    suspend fun completeHydration(turnRunning: Boolean = false) {
        mutex.withLock {
            val running = turnRunning || heldTurnEnd != null
            debug {
                "completeHydration: committing buffered messages=${buffered.messages.size}, " +
                    "bufferedStreaming=${buffered.isStreaming}"
            }
            // session/load never replays a TurnComplete, so every assistant bubble built by
            // the reducer is left isStreaming=true. Finalize ALL of them (not just the last)
            // so a loaded transcript can never carry a stray streaming flag — and derive the
            // snapshot flag from the messages instead of hard-coding it, keeping the two in sync.
            val finalizedMessages =
                if (running) {
                    SessionMessageReducer.finishStreaming(buffered.messages)
                } else {
                    buffered.messages.map { message ->
                        if (message.isStreaming) message.copy(isStreaming = false) else message
                    }
                }
            // A replay that brings no config keeps the attached session's: a gap refill
            // starts after attach already pushed it, and the raw load reply is not mapped.
            // shortcut: a replay that truly clears every option keeps the old ones until the next update.
            val config = if (buffered.hasConfig) buffered else live
            live =
                buffered.copy(
                    epoch = live.epoch,
                    messages = finalizedMessages,
                    isStreaming = running || finalizedMessages.any { it.isStreaming },
                    title = buffered.title ?: live.title,
                    // A load replay carries no usage update, so keep the last known
                    // context reading instead of dropping it to null.
                    usage = buffered.usage ?: live.usage,
                    nativeConfigOptions = config.nativeConfigOptions,
                    legacyModes = config.legacyModes,
                    legacyModel = config.legacyModel,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun failHydration(error: String?) {
        mutex.withLock {
            debug { "failHydration: error=${error ?: "unknown"}" }
            _snapshot.value =
                _snapshot.value.copy(
                    loadState = SessionLoadState.FAILED,
                    error = error,
                    isStreaming = false,
                    turnStatus = TurnStatus.UNKNOWN,
                    turnStatusSince = null,
                )
        }
    }

    suspend fun markReady() {
        mutex.withLock {
            if (_snapshot.value.loadState == SessionLoadState.HYDRATING) {
                debug { "markReady: ignored while HYDRATING" }
                return@withLock
            }
            debug { "markReady: publishing READY with liveMessages=${live.messages.size}" }
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    /**
     * Starts a (re)attach. It re-establishes the connection, not the last turn's outcome: the
     * transport that carried it is gone, so a retained FINISHED/ERROR is no longer current
     * evidence and must not be republished as one. Cleared here rather than once attached, so an
     * outcome the gateway reports during the attach (`_ferngeist/turn_result`) stands.
     *
     * Until [settleAttach], a turn end is held rather than applied: the gateway sends it after the
     * updates it ends, but the SDK runs its handler at once while it holds those updates back
     * until the attach reply.
     */
    suspend fun beginAttach() {
        mutex.withLock {
            attaching = true
            heldTurnEnd = null
            dropsRepeatPrompts = null
            historyGap = false
            _snapshot.value = _snapshot.value.copy(dropsRepeatPrompts = null, historyGap = false)
            live =
                live.copy(
                    turnOutcome = TurnStatus.UNKNOWN,
                    turnStartedAt = null,
                    turnFinishedAt = null,
                )
        }
    }

    /**
     * Ends the attach: the turn end it held closes the turn now, after every update the attach
     * replayed; without one, a turn no longer [turnRunning] closes unheard. [dropsRepeatPrompts]:
     * the gateway answered for a session it still holds, so it remembers the prompts it delivered.
     * [historyGap]: the attach missed updates it had no way to refill.
     */
    suspend fun settleAttach(
        turnRunning: Boolean,
        dropsRepeatPrompts: Boolean = false,
        historyGap: Boolean = false,
    ) {
        val end =
            mutex.withLock {
                attaching = false
                this.dropsRepeatPrompts = dropsRepeatPrompts
                this.historyGap = historyGap
                _snapshot.value =
                    _snapshot.value.copy(dropsRepeatPrompts = dropsRepeatPrompts, historyGap = historyGap)
                heldTurnEnd.also { heldTurnEnd = null }
            }
        when {
            end != null -> onEvent(end)
            !turnRunning -> onEvent(AppSessionEvent.TurnEndUnheard)
        }
    }

    /** Applies [event]; [updateSeq] is the gateway's number for the update it came from, if any. */
    suspend fun onEvent(
        event: AppSessionEvent,
        updateSeq: Long? = null,
    ) {
        mutex.withLock {
            if (attaching && event is AppSessionEvent.TurnComplete) {
                debug { "onEvent: holding ${event.stopReason} until the attach settles" }
                heldTurnEnd = event
                return@withLock
            }
            val seq = ++eventSeq
            val loadState = _snapshot.value.loadState
            debug {
                "event#$seq state=$loadState type=${event::class.simpleName} " +
                    "summary=${summarizeEvent(event)}"
            }
            if (_snapshot.value.loadState == SessionLoadState.HYDRATING) {
                val open =
                    buffered.messages
                        .lastOrNull()
                        ?.takeIf { it.isStreaming }
                        ?.id
                buffered = reduce(buffered, event, nowMs = null, eventSeq).after(updateSeq)
                // The gateway reports a turn that ended while detached ahead of the load reply; keep
                // its reply and duration so the hydrated transcript stamps the turn.
                if (event is AppSessionEvent.TurnComplete) {
                    buffered = buffered.copy(openReplyId = open, turnDurationMs = event.durationMs)
                }
                debug {
                    "event#$seq bufferedApplied messages=${buffered.messages.size} " +
                        "streaming=${buffered.isStreaming}"
                }
                return@withLock
            }
            live = reduce(live, event, clock(), eventSeq).after(updateSeq)
            // A reply closed by a reattach ended while this client was away; stamping it now would
            // time the outage, not the turn, so it gets no time at all.
            when (event) {
                is AppSessionEvent.TurnEndUnheard ->
                    live = live.copy(openReplyId = null, turnStartedAt = null, turnFinishedAt = null)
                is AppSessionEvent.TurnComplete ->
                    live =
                        live.copy(turnDurationMs = event.durationMs, turnFinishedAt = TimeSource.Monotonic.markNow())
                else -> Unit
            }
            debug { "event#$seq liveApplied messages=${live.messages.size} streaming=${live.isStreaming}" }
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun onLocalPromptStarted(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        clientId: String? = null,
    ) {
        mutex.withLock {
            if (_snapshot.value.loadState != SessionLoadState.READY) {
                debug { "onLocalPromptStarted ignored: loadState=${_snapshot.value.loadState}" }
                return@withLock
            }

            debug { "onLocalPromptStarted textLen=${text.length}, images=${images.size}, files=${files.size}" }
            val prompt = UserContent(text, images, files, clientId)
            val withUser = SessionMessageReducer.appendLocalUserMessage(live.messages, prompt)
            val withAssistantPlaceholder = SessionMessageReducer.startStreaming(withUser)

            live =
                live.copy(
                    messages = withAssistantPlaceholder,
                    isStreaming = true,
                    stoppedAt = null,
                    // The new turn has no outcome yet, and the previous turn's unanswered
                    // requests cannot still be pending on it.
                    turnOutcome = TurnStatus.UNKNOWN,
                    pendingPermissions = emptySet(),
                    turnSettled = false,
                    turnStartedAt = TimeSource.Monotonic.markNow(),
                    turnFinishedAt = null,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun onPromptSteered(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        clientId: String?,
        injected: Boolean,
    ) {
        mutex.withLock {
            if (_snapshot.value.loadState != SessionLoadState.READY) return@withLock
            // Neither adapter echoes a steered prompt, so this bubble is its only record.
            // A codex startedNewTurn is a turn this client never sees end: no placeholder
            // for it, or it would spin forever.
            val withUser =
                SessionMessageReducer.appendLocalUserMessage(
                    live.messages,
                    UserContent(text, images, files, clientId),
                    steered = true,
                )
            live =
                live.copy(
                    messages = if (injected) SessionMessageReducer.startStreaming(withUser) else withUser,
                    stoppedAt = null,
                    // A steered prompt starts or continues a turn, so any earlier outcome no
                    // longer describes what this session is doing.
                    turnOutcome = TurnStatus.UNKNOWN,
                    turnSettled = false,
                    turnStartedAt = if (live.isStreaming) live.turnStartedAt else TimeSource.Monotonic.markNow(),
                    turnFinishedAt = null,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    /** [clientId]'s local bubble goes too: the chat's queue shows the prompt FAILED for a retry. */
    suspend fun onPromptSendFailed(clientId: String?) {
        mutex.withLock {
            debug { "onPromptSendFailed: finishing local streaming placeholder" }
            val unsent = live.messages.filterNot { clientId != null && it.clientId == clientId }
            live =
                live.copy(
                    messages = failUnfinishedToolCalls(SessionMessageReducer.finishStreaming(unsent)),
                    isStreaming = false,
                    // A terminal event that already settled this turn owns the outcome (a
                    // cancelled turn stays cancelled); only a send that failed with no
                    // outcome of its own is an error.
                    turnOutcome = if (live.turnSettled) live.turnOutcome else TurnStatus.ERROR,
                    turnCompletionSequence = if (live.turnSettled) live.turnCompletionSequence else ++eventSeq,
                    pendingPermissions = emptySet(),
                    pendingElicitations = emptyList(),
                    turnSettled = true,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun onLocalCancel() {
        mutex.withLock {
            debug { "onLocalCancel: finishing local streaming placeholder" }
            live =
                live.copy(
                    messages =
                        failUnfinishedToolCalls(
                            SessionMessageReducer.finishStreaming(live.messages),
                        ),
                    isStreaming = false,
                    stoppedAt = live.messages.lastOrNull()?.id,
                    // A cancelled turn is not a completed one; it proves no outcome.
                    turnOutcome = TurnStatus.UNKNOWN,
                    pendingPermissions = emptySet(),
                    pendingElicitations = emptyList(),
                    turnSettled = true,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    /** The live state, for saving; immutable, so it is read without blocking any event. */
    internal val state: RuntimeData get() = live

    /**
     * Installs [data] from an earlier process and publishes it; a streaming turn stays streaming.
     * A permission prompt is dropped: its request died with the process, and the gateway re-sends
     * the ones still open.
     */
    internal suspend fun install(data: RuntimeData) {
        mutex.withLock {
            live = data.copy(messages = data.messages.map { it.withoutPermissionPrompts() })
            eventSeq = data.turnCompletionSequence
            // What the gateway remembers is learnt by the attach this restore precedes.
            dropsRepeatPrompts = null
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    /**
     * Where this state left off in the gateway's numbering under [epoch] (any epoch, when null), or
     * null when it follows another or none. A new [epoch] is followed from here, restarting the count.
     */
    suspend fun catchUpFrom(epoch: String?): CatchUpCursor? =
        mutex.withLock {
            val held = live.epoch
            if (epoch != null && held != epoch) live = live.copy(epoch = epoch, lastSeq = 0)
            CatchUpCursor(held ?: return@withLock null, live.lastSeq).takeIf { epoch == null || held == epoch }
        }

    companion object {
        private const val TAG = "TSRuntime"

        /**
         * Pure function that reduces an event onto the current state.
         *
         * Message list changes are delegated to [SessionMessageReducer.handleEvent].
         * Side-field updates (usage, commands, config options, legacy mode/model) are handled
         * directly here. The branching is straightforward: each event type updates exactly one
         * field or a small related group.
         *
         * Config option values go through [SessionConfigPolicy.applyValueChange], which owns origin handling.
         */
        private fun reduce(
            current: RuntimeData,
            event: AppSessionEvent,
            nowMs: Long?,
            eventSeq: Long,
        ): RuntimeData {
            val transcript = reduceTranscript(current, event, nowMs)
            val fields = applySideFieldUpdates(current, event)

            // TurnComplete / TurnEndUnheard end the streaming turn.
            val endsTurn = event is AppSessionEvent.TurnComplete || event is AppSessionEvent.TurnEndUnheard
            // Only the trailing bubble can be mid-stream: finishStreaming clears the flag on
            // every message it closes, and a new streaming bubble is always appended last.
            // A full scan is O(messages) and this runs per event.
            val derivedStreaming =
                (current.isStreaming && !endsTurn) || transcript.messages.lastOrNull()?.isStreaming == true
            return current.copy(
                messages = transcript.messages,
                toolCallIndex = transcript.toolCallIndex,
                isStreaming = derivedStreaming,
                usage = fields.usage,
                availableCommands = fields.availableCommands,
                commandsAdvertised = fields.commandsAdvertised,
                nativeConfigOptions = fields.nativeConfigOptions,
                legacyModes = fields.legacyModes,
                legacyModel = fields.legacyModel,
                title = fields.title,
                // A turn that has ended cannot still be waiting on the user: a request it left
                // behind is unanswerable, so it must not keep the session in attention across a
                // reconnect (the agent may never send its resolve event after a drop). Clearing on
                // a turn end only - not on a load replay's close - keeps a request the agent
                // raises while a load runs answerable.
                pendingElicitations =
                    if (event is AppSessionEvent.TurnComplete) {
                        emptyList()
                    } else {
                        applyElicitationUpdate(current.pendingElicitations, event)
                    },
                pendingPermissions =
                    if (event is AppSessionEvent.TurnComplete) {
                        emptySet()
                    } else {
                        pendingPermissionsAfter(current.pendingPermissions, event)
                    },
                turnOutcome = turnOutcomeAfter(current.turnOutcome, event, current.isStreaming),
                turnSettled = current.turnSettled || event is AppSessionEvent.TurnComplete,
                turnCompletionSequence =
                    if (event is AppSessionEvent.TurnComplete) eventSeq else current.turnCompletionSequence,
            )
        }

        /** Folds one event into the transcript: a stop's tail, the reducer, and the turn-close fold. */
        private fun reduceTranscript(
            current: RuntimeData,
            event: AppSessionEvent,
            nowMs: Long?,
        ): ReducerResult {
            // Text arriving after a stop would otherwise land in the stopped reply, and its
            // turn-close fold would glue it onto that reply's last sentence.
            val stoppedReply = current.messages.lastOrNull()?.takeIf { it.id == current.stoppedAt }
            val opensReply =
                stoppedReply?.segments?.isNotEmpty() == true &&
                    (
                        event is AppSessionEvent.AgentMessage ||
                            event is AppSessionEvent.AgentThought ||
                            event is AppSessionEvent.PlanUpdated
                    )
            val opened = if (opensReply) SessionMessageReducer.startStreaming(current.messages) else current.messages

            // TurnEndUnheard only flips isStreaming; everything else goes through the reducer,
            // which owns both the message list and the tool-call index
            val fromReducer =
                when (event) {
                    is AppSessionEvent.TurnEndUnheard -> null
                    else -> SessionMessageReducer.handleEvent(opened, current.toolCallIndex, event, nowMs)
                }
            val reduced = fromReducer ?: ReducerResult(opened, current.toolCallIndex)
            if (fromReducer == null || current.stoppedAt != null) {
                // ponytail: re-folds the trailing reply per late event; fine for a stop's short tail.
                return reduced.copy(messages = SessionMessageReducer.finishStreaming(reduced.messages))
            }
            return reduced
        }

        /**
         * Applies side-field updates (usage, commands, config options, legacy mode/model)
         * for a single event. Returns the new field values; unchanged fields carry through.
         */
        private fun applySideFieldUpdates(
            current: RuntimeData,
            event: AppSessionEvent,
        ): SideFields {
            var usage = current.usage
            var availableCommands = current.availableCommands
            var commandsAdvertised = current.commandsAdvertised
            var nativeConfigOptions = current.nativeConfigOptions
            var legacyModes = current.legacyModes
            var legacyModel = current.legacyModel
            var title = current.title

            when (event) {
                is AppSessionEvent.TurnComplete -> Unit
                is AppSessionEvent.TurnEndUnheard -> Unit
                is AppSessionEvent.UsageUpdated -> usage = mapUsage(event)
                is AppSessionEvent.CommandsUpdated -> {
                    availableCommands = event.commands
                    commandsAdvertised = true
                }
                is AppSessionEvent.ModeChanged -> legacyModes = confirmModeChanged(legacyModes, event.modeId)
                is AppSessionEvent.ModesUpdated -> legacyModes = mapModes(event, legacyModes)
                is AppSessionEvent.ConfigOptionsUpdated -> nativeConfigOptions = event.options
                is AppSessionEvent.ConfigOptionValueChanged -> {
                    nativeConfigOptions = applyConfigValueChange(nativeConfigOptions, event)
                }
                is AppSessionEvent.LegacyModelOptionsUpdated -> legacyModel = mapLegacyModel(event)
                is AppSessionEvent.ModelSelectionConfirmed -> {
                    legacyModel = confirmModelSelection(legacyModel, event.modelId)
                }
                is AppSessionEvent.SessionInfoUpdated -> title = event.title ?: title
                else -> Unit
            }
            return SideFields(
                usage = usage,
                availableCommands = availableCommands,
                commandsAdvertised = commandsAdvertised,
                nativeConfigOptions = nativeConfigOptions,
                legacyModes = legacyModes,
                legacyModel = legacyModel,
                title = title,
            )
        }

        /**
         * Every path that closes a reply (turn end, cancel, a failed send, a steer, a new prompt)
         * publishes, so stamping there catches them all without each one knowing about time. The turn
         * runs from its prompt's send; replayed history never streams here, so it is never stamped.
         * The gateway's duration wins over the wall clock: a dozing phone hears the end late.
         */
        private fun stampClosedReply(
            data: RuntimeData,
            wallClock: () -> Long,
        ): RuntimeData {
            val messages = data.messages
            val index = data.openReplyId?.let { id -> messages.indexOfLast { it.id == id } } ?: -1
            val reply = messages.getOrNull(index)?.takeIf { !it.isStreaming && it.turn == null }
            val prompt = messages.subList(0, index.coerceAtLeast(0)).lastOrNull { it.role == ChatMessage.Role.USER }
            val stamped =
                if (reply != null && prompt != null) {
                    val finishedAt = data.turnDurationMs?.let { prompt.createdAt + it } ?: wallClock()
                    data.copy(
                        messages =
                            messages.toMutableList().apply {
                                this[index] = reply.copy(turn = TurnTiming(prompt.createdAt, finishedAt))
                            },
                    )
                } else {
                    data
                }
            val last = stamped.messages.lastOrNull()
            return stamped.copy(turnDurationMs = null, openReplyId = if (last?.isStreaming == true) last.id else null)
        }
    }

    private data class SideFields(
        val usage: SessionUsage?,
        val availableCommands: List<CommandInfo>,
        val commandsAdvertised: Boolean,
        val nativeConfigOptions: List<SessionConfigOption>,
        val legacyModes: LegacyModeState?,
        val legacyModel: LegacyModelState?,
        val title: String?,
    )

    /**
     * What the session is doing right now: a request awaiting the user outranks the running
     * turn it belongs to, a running turn outranks the last outcome, and with no turn in
     * flight the last outcome stands (or unknown, which claims nothing).
     */
    private val RuntimeData.turnStatus: TurnStatus
        get() =
            when {
                awaitsAttention -> TurnStatus.NEEDS_ATTENTION
                isStreaming -> TurnStatus.WORKING
                else -> turnOutcome
            }

    /** A permission prompt or a session-scoped elicitation is blocking the agent on the user. */
    private val RuntimeData.awaitsAttention: Boolean
        get() = pendingElicitations.isNotEmpty() || pendingPermissions.isNotEmpty()

    /** Any of the session's config, native or legacy. */
    private val RuntimeData.hasConfig: Boolean
        get() = nativeConfigOptions.isNotEmpty() || legacyModes != null || legacyModel != null

    /**
     * Publishes the [live] state to the [_snapshot] StateFlow after resolving the effective
     * config options list.
     *
     * Config resolution via [SessionConfigPolicy.resolveEffectiveOptions] unifies native
     * options with synthetic legacy mode/model options. This ensures the UI always sees a
     * complete set of options regardless of what the agent provides natively.
     *
     * The snapshot's `configOptions` field contains the resolved list; `nativeConfigOptions`
     * stays internal to the runtime.
     */
    private fun publishLive(
        loadState: SessionLoadState,
        error: String?,
    ) {
        live = stampClosedReply(live, wallClock)
        val effectiveConfigOptions =
            SessionConfigPolicy.resolveEffectiveOptions(
                nativeOptions = live.nativeConfigOptions,
                legacyModes = live.legacyModes,
                legacyModel = live.legacyModel,
            )
        val last = live.messages.lastOrNull()
        val lastRole = last?.role?.name ?: "none"
        val lastLen = last?.content?.length ?: 0
        val lastSegments = last?.segments?.size ?: 0
        _snapshot.value =
            SessionSnapshot(
                sessionId = sessionId,
                loadState = loadState,
                messages = live.messages,
                isStreaming = live.isStreaming,
                usage = live.usage,
                availableCommands = live.availableCommands,
                commandsAdvertised = live.commandsAdvertised,
                configOptions = effectiveConfigOptions,
                title = live.title,
                error = error,
                pendingElicitations = live.pendingElicitations,
                turnStatus = live.turnStatus,
                turnStatusSince =
                    when (live.turnStatus) {
                        TurnStatus.WORKING, TurnStatus.NEEDS_ATTENTION -> live.turnStartedAt
                        TurnStatus.FINISHED -> live.turnFinishedAt
                        else -> null
                    },
                turnCompletionSequence = live.turnCompletionSequence,
                dropsRepeatPrompts = dropsRepeatPrompts,
                historyGap = historyGap,
            )
        debug {
            "publishLive state=$loadState messages=${live.messages.size} streaming=${live.isStreaming} " +
                "lastRole=$lastRole lastLen=$lastLen lastSegments=$lastSegments " +
                "commands=${live.availableCommands.size} configOptions=${effectiveConfigOptions.size}"
        }
    }

    private fun debug(message: () -> String) {
        SessionDebug.d(TAG, sessionId, message)
    }
}

private fun ChatMessage.withoutPermissionPrompts(): ChatMessage {
    if (segments.none { it.toolCall?.permissionRequestId != null }) return this
    return copy(
        segments =
            segments
                .map { segment ->
                    val call = segment.toolCall?.takeIf { it.permissionRequestId != null } ?: return@map segment
                    segment.copy(toolCall = call.copy(permissionOptions = null, permissionRequestId = null))
                }.toPersistentList(),
    )
}

/** Where a saved state left off in the gateway's update numbering for one agent process. */
data class CatchUpCursor(
    val epoch: String,
    val afterSeq: Long,
)
