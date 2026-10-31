package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.TurnTiming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

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
    companion object {
        private const val TAG = "TSRuntime"
    }

    /** Internal state snapshot. All fields are immutable; [reduce] produces new instances. */
    private data class RuntimeData(
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
    )

    private val mutex = Mutex()
    private var live = RuntimeData()
    private var buffered = RuntimeData()
    private var eventSeq = 0L

    /** The reply still streaming at the last publish, so its close can be stamped. */
    private var openReplyId: String? = null

    /** The gateway's measure of the turn that just ended, for [stampClosedReply]. */
    private var turnDurationMs: Long? = null

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

    suspend fun completeHydration() {
        mutex.withLock {
            debug {
                "completeHydration: committing buffered messages=${buffered.messages.size}, " +
                    "bufferedStreaming=${buffered.isStreaming}"
            }
            // session/load never replays a TurnComplete, so every assistant bubble built by
            // the reducer is left isStreaming=true. Finalize ALL of them (not just the last)
            // so a loaded transcript can never carry a stray streaming flag — and derive the
            // snapshot flag from the messages instead of hard-coding it, keeping the two in sync.
            val finalizedMessages =
                buffered.messages.map { message ->
                    if (message.isStreaming) message.copy(isStreaming = false) else message
                }
            live =
                buffered.copy(
                    messages = finalizedMessages,
                    isStreaming = finalizedMessages.any { it.isStreaming },
                    title = buffered.title ?: live.title,
                    // A load replay carries no usage update, so keep the last known
                    // context reading instead of dropping it to null.
                    usage = buffered.usage ?: live.usage,
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
            // A (re)registered session re-establishes the connection, not the last turn's
            // outcome: the transport that carried it is gone, so a retained FINISHED/ERROR is
            // no longer current evidence and must not be republished as one.
            live = live.copy(turnOutcome = TurnStatus.UNKNOWN, turnStartedAt = null, turnFinishedAt = null)
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun onEvent(event: AppSessionEvent) {
        mutex.withLock {
            val seq = ++eventSeq
            val loadState = _snapshot.value.loadState
            debug {
                "event#$seq state=$loadState type=${event::class.simpleName} " +
                    "summary=${summarizeEvent(event)}"
            }
            if (_snapshot.value.loadState == SessionLoadState.HYDRATING) {
                buffered = reduce(buffered, event, nowMs = null)
                debug {
                    "event#$seq bufferedApplied messages=${buffered.messages.size} " +
                        "streaming=${buffered.isStreaming}"
                }
                return@withLock
            }
            live = reduce(live, event, clock())
            // A reply closed by a reattach ended while this client was away; stamping it now would
            // time the outage, not the turn, so it gets no time at all.
            when (event) {
                is AppSessionEvent.SessionLoadComplete -> {
                    openReplyId = null
                    live = live.copy(turnStartedAt = null, turnFinishedAt = null)
                }
                is AppSessionEvent.TurnComplete -> {
                    turnDurationMs = event.durationMs
                    live = live.copy(turnFinishedAt = TimeSource.Monotonic.markNow())
                }
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
    ) {
        mutex.withLock {
            if (_snapshot.value.loadState != SessionLoadState.READY) {
                debug { "onLocalPromptStarted ignored: loadState=${_snapshot.value.loadState}" }
                return@withLock
            }

            debug { "onLocalPromptStarted textLen=${text.length}, images=${images.size}, files=${files.size}" }
            val withUser = SessionMessageReducer.appendLocalUserMessage(live.messages, text, images, files)
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
                    text,
                    images,
                    files,
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

    suspend fun onPromptSendFailed() {
        mutex.withLock {
            debug { "onPromptSendFailed: finishing local streaming placeholder" }
            live =
                live.copy(
                    messages =
                        failUnfinishedToolCalls(
                            SessionMessageReducer.finishStreaming(live.messages),
                        ),
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

    /**
     * Pure function that reduces an event onto the current state.
     *
     * Message list changes are delegated to [SessionMessageReducer.handleEvent].
     * Side-field updates (usage, commands, config options, legacy mode/model) are handled
     * directly here. The branching is straightforward: each event type updates exactly one
     * field or a small related group.
     *
     * Config option value changes use [SessionConfigPolicy.applyValueChange] to ensure
     * type-safe updates without duplicating the origin-handling logic.
     */
    private fun reduce(
        current: RuntimeData,
        event: AppSessionEvent,
        nowMs: Long?,
    ): RuntimeData {
        val transcript = reduceTranscript(current, event, nowMs)
        val fields = applySideFieldUpdates(current, event)

        // TurnComplete / SessionLoadComplete end the streaming turn.
        val endsTurn = event is AppSessionEvent.TurnComplete || event is AppSessionEvent.SessionLoadComplete
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
            turnOutcome = turnOutcomeAfter(current.turnOutcome, event),
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

        // SessionLoadComplete only flips isStreaming; everything else goes through the reducer,
        // which owns both the message list and the tool-call index
        val fromReducer =
            when (event) {
                is AppSessionEvent.SessionLoadComplete -> null
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
            is AppSessionEvent.SessionLoadComplete -> Unit
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
        stampClosedReply()
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
            )
        debug {
            "publishLive state=$loadState messages=${live.messages.size} streaming=${live.isStreaming} " +
                "lastRole=$lastRole lastLen=$lastLen lastSegments=$lastSegments " +
                "commands=${live.availableCommands.size} configOptions=${effectiveConfigOptions.size}"
        }
    }

    /**
     * Every path that closes a reply (turn end, cancel, a failed send, a steer, a new prompt)
     * publishes, so stamping here catches them all without each one knowing about time. The turn
     * runs from its prompt's send; replayed history never streams here, so it is never stamped.
     * The gateway's duration wins over the wall clock: a dozing phone hears the end late.
     */
    private fun stampClosedReply() {
        val messages = live.messages
        val index = openReplyId?.let { id -> messages.indexOfLast { it.id == id } } ?: -1
        val reply = messages.getOrNull(index)?.takeIf { !it.isStreaming && it.turn == null }
        val prompt = messages.subList(0, index.coerceAtLeast(0)).lastOrNull { it.role == ChatMessage.Role.USER }
        if (reply != null && prompt != null) {
            val finishedAt = turnDurationMs?.let { prompt.createdAt + it } ?: wallClock()
            live =
                live.copy(
                    messages =
                        messages.toMutableList().apply {
                            this[index] = reply.copy(turn = TurnTiming(prompt.createdAt, finishedAt))
                        },
                )
        }
        turnDurationMs = null
        val last = live.messages.lastOrNull()
        openReplyId = if (last?.isStreaming == true) last.id else null
    }

    private fun debug(message: () -> String) {
        SessionDebug.d(TAG, sessionId, message)
    }
}
