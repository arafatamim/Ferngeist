package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement

/**
 * How many raw events [SessionBridge.events] replays to a collector that attaches late.
 * The stream has no app-level consumer, so the tail only needs to cover the turn(s) in
 * flight — a few update events and their terminal event — not a whole session history.
 */
private const val EVENT_REPLAY_TAIL = 8

/**
 * SessionBridge is the UI-facing handle to a single ACP session, implementing [SessionPort].
 *
 * It owns a [SessionStateEngine] (the runtime) and forwards user actions to the
 * [AcpConnectionManager]. Events from the SDK are fed into the runtime via [emitEvent],
 * which also forwards them to the raw [events] stream and to the narrow
 * [modelSelectionEvents] flow.
 *
 * ## Interface contract
 * SessionBridge implements [SessionPort] so the chat layer never imports the concrete
 * type. Methods that are NOT on [SessionPort] — [emitEvent], [beginHydration],
 * [completeHydration], [failHydration], [markReady] — are called exclusively by
 * [AcpConnectionManager] (which retains a [SessionBridge] reference internally).
 *
 * ## Lifecycle
 * An internal event scope CoroutineScope backs [startTurn]: a prompt turn must outlive
 * the screen that started it, so it runs on that scope rather than the caller's. The
 * scope is cancelled in [close], which is called by [AcpSessionRegistry.clearSession] or
 * [AcpSessionRegistry.clearAll] when the session is torn down.
 */
class SessionBridge(
    override val sessionId: String,
    private val connectionManager: AcpConnectionManager?,
) : SessionPort {
    internal val runtime: SessionStateEngine = SessionRuntime(sessionId = sessionId)
    override val snapshot: StateFlow<SessionSnapshot> = runtime.snapshot

    // Raw event stream, for an observer that needs the events as they are emitted. No app
    // code collects it — the chat UI renders from [snapshot] — so the replay tail is kept
    // short instead of the 5000 entries it used to retain: a live session held every
    // assistant message, tool-call payload and plan it had ever seen alive for readers that
    // no longer exist. Only the tail is replayed to a late collector.
    private val _events =
        MutableSharedFlow<AppSessionEvent>(
            replay = EVENT_REPLAY_TAIL,
            extraBufferCapacity = 2048,
        )
    val events: SharedFlow<AppSessionEvent> = _events.asSharedFlow()

    private val _modelSelectionEvents =
        MutableSharedFlow<AppSessionEvent.ModelSelectionConfirmed>(
            replay = 1,
            extraBufferCapacity = 1,
        )

    /**
     * Narrow, purpose-built flow of [AppSessionEvent.ModelSelectionConfirmed] events, exposed
     * via [SessionPort.modelSelectionEvents] so consumers can react to model changes without
     * importing the concrete bridge type. Fed by an explicit type check in [emitEvent]; only
     * this event type is retained.
     */
    override val modelSelectionEvents: SharedFlow<AppSessionEvent.ModelSelectionConfirmed> =
        _modelSelectionEvents.asSharedFlow()

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val traceTag = "TSBridge"

    /**
     * Feeds a session event into the runtime reducer and then emits it to observers.
     *
     * Ordering matters: runtime state is updated before observers see the event.
     */
    suspend fun emitEvent(event: AppSessionEvent) {
        debug { "emitEvent type=${event::class.simpleName}" }
        runtime.onEvent(event)
        _events.emit(event)
        if (event is AppSessionEvent.ModelSelectionConfirmed) {
            _modelSelectionEvents.emit(event)
        }
    }

    /**
     * Launches a prompt turn on the bridge's own [eventScope] so the turn survives
     * cancellation of its caller. A chat screen's `viewModelScope` is cancelled when
     * the user navigates away; collecting the prompt there would make the SDK send a
     * `$/cancelRequest`, aborting the agent mid-turn and leaving the transcript with
     * no terminal event. A turn dies with its session, not with the screen.
     */
    internal fun startTurn(block: suspend () -> Unit): Deferred<Unit> = eventScope.async { block() }

    /** Marks the session as entering hydration (history replay) mode. */
    suspend fun beginHydration() {
        runtime.beginHydration()
    }

    /** Marks hydration as completed and transitions the runtime toward ready. */
    suspend fun completeHydration() {
        runtime.completeHydration()
    }

    /** Marks hydration as failed and stores the failure message. */
    suspend fun failHydration(error: String?) {
        runtime.failHydration(error)
    }

    /** Marks the session as ready once initial state has been fully built. */
    suspend fun markReady() {
        runtime.markReady()
    }

    /**
     * Sends a prompt to the server while updating local state optimistically.
     *
     * The runtime is updated first so the UI shows the outgoing message immediately.
     * If the transport call fails, the runtime is informed so it can rollback state.
     */
    override suspend fun sendPrompt(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ) {
        runtime.onLocalPromptStarted(text, images, files)
        sendWithRollback(text, images, files)
    }

    /**
     * Sends the prompt through the connection manager; on any failure rolls back the
     * optimistic message and rethrows so the caller can surface the error.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendWithRollback(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ) {
        try {
            connectionManager?.sendSessionMessage(sessionId, text, images, files)
        } catch (e: CancellationException) {
            // Propagate structured cancellation without swallowing; the coroutine's
            // parent will handle it and the streaming placeholder remains cancellable.
            throw e
        } catch (e: Exception) {
            // Any transport, SDK, or protocol error (IOException, IllegalStateException,
            // RuntimeException from JSON/codec, etc.) must roll back the optimistic
            // bubble and clear the streaming flag, otherwise the loading indicator
            // lingers forever on a failed turn.
            runtime.onPromptSendFailed()
            throw e
        }
    }

    /**
     * Requests a streaming cancel on the transport and updates local state.
     */
    override suspend fun cancel() {
        connectionManager?.cancelSession(sessionId)
        runtime.onLocalCancel()
    }

    /**
     * Updates a configuration option on the session.
     *
     * This method delegates all routing logic to [SessionConfigPolicy.mapToDispatchAction],
     * which decides whether the option is a legacy mode, legacy model, or native config, and
     * returns the corresponding RPC call and optimistic event to emit.
     *
     * The previous implementation contained a 30-line when-branch directly handling the three
     * cases. Extracting this into the policy centralizes config compatibility logic and reduces
     * branching in the bridge.
     */
    override suspend fun setConfigOption(
        optionId: String,
        value: SessionConfigValue,
    ) {
        val option = snapshot.value.configOptions.firstOrNull { it.id == optionId }
        val action = SessionConfigPolicy.mapToDispatchAction(option, value) ?: return
        when (action) {
            is SessionConfigPolicy.DispatchAction.SetLegacyMode -> {
                connectionManager?.setSessionMode(sessionId, action.modeId)
                runtime.onEvent(action.event)
            }

            is SessionConfigPolicy.DispatchAction.SetLegacyModel -> {
                connectionManager?.setSessionModel(sessionId, action.modelId)
                runtime.onEvent(action.event)
            }

            is SessionConfigPolicy.DispatchAction.SetNativeConfig -> {
                connectionManager?.setSessionConfigOption(sessionId, action.optionId, action.value)
                runtime.onEvent(action.event)
            }
        }
    }

    override suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        connectionManager?.respondPermissionSelected(sessionId, toolCallId, optionId)
    }

    /** Declines a permission prompt for a tool call. */
    override suspend fun denyPermission(toolCallId: String) {
        connectionManager?.respondPermissionCancelled(sessionId, toolCallId)
    }

    /**
     * Cancels the internal [eventScope], killing in-flight prompt turns. Called by
     * [AcpSessionRegistry] when the session is removed. After close, nothing more is
     * emitted through [modelSelectionEvents].
     */
    fun close() {
        eventScope.cancel()
    }

    /** Emits a debug log entry if logging is available. */
    private fun debug(message: () -> String) {
        SessionDebug.d(traceTag, sessionId, message)
    }
}

sealed interface AppSessionEvent {
    data class UserMessage(
        val text: String,
        val images: List<ChatImageData> = emptyList(),
        val files: List<ChatFileData> = emptyList(),
        val append: Boolean = false,
        val timestampMs: Long? = null,
    ) : AppSessionEvent

    data class AgentMessage(
        val text: String,
        val timestampMs: Long? = null,
    ) : AppSessionEvent

    data class AgentThought(
        val text: String,
        val timestampMs: Long? = null,
    ) : AppSessionEvent

    data class ToolCallStarted(
        val toolCallId: String,
        val title: String,
        val kind: ToolKind?,
        val status: ToolCallStatus?,
        val rawInput: JsonElement? = null,
    ) : AppSessionEvent

    data class ToolCallUpdated(
        val toolCallId: String,
        val status: ToolCallStatus?,
        val title: String?,
        val kind: ToolKind?,
        val content: List<ToolCallContent>? = null,
        val rawInput: JsonElement? = null,
        val rawOutput: JsonElement? = null,
    ) : AppSessionEvent

    data class ToolPermissionRequested(
        val toolCallId: String,
        val requestId: String,
        val title: String?,
        val options: List<SessionPermissionOption>,
    ) : AppSessionEvent

    data class ToolPermissionResolved(
        val toolCallId: String,
    ) : AppSessionEvent

    data class ModeChanged(
        val modeId: String,
    ) : AppSessionEvent

    data class ModesUpdated(
        val modes: List<SessionMode>,
        val currentModeId: String? = null,
    ) : AppSessionEvent

    data class ConfigOptionsUpdated(
        val options: List<SessionConfigOption>,
    ) : AppSessionEvent

    data class ConfigOptionValueChanged(
        val optionId: String,
        val value: SessionConfigValue,
    ) : AppSessionEvent

    data class LegacyModelOptionsUpdated(
        val choices: List<SessionConfigChoice>,
        val currentModelId: String? = null,
    ) : AppSessionEvent

    data class ModelSelectionConfirmed(
        val modelId: String?,
    ) : AppSessionEvent

    data class PlanUpdated(
        val entries: List<PlanEntry>,
        val timestampMs: Long? = null,
    ) : AppSessionEvent

    data class UsageUpdated(
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val cachedReadTokens: Int? = null,
        val contextWindowTokens: Int? = null,
        val costAmount: Double? = null,
        val costCurrency: String? = null,
    ) : AppSessionEvent

    data class CommandsUpdated(
        val commands: List<CommandInfo>,
    ) : AppSessionEvent

    data class SessionInfoUpdated(
        val title: String?,
        val updatedAt: String?,
    ) : AppSessionEvent

    /** Synthetic event emitted after session/load replay events have been forwarded. */
    data object SessionLoadComplete : AppSessionEvent

    data class TurnComplete(
        val stopReason: String,
    ) : AppSessionEvent

    data class Unknown(
        val raw: String,
    ) : AppSessionEvent
}

data class SessionMode(
    val id: String,
    val name: String,
    val description: String? = null,
)

data class SessionPermissionOption(
    val id: String,
    val label: String,
    val kind: String? = null,
)
