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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/**
 * SessionBridge is the UI-facing handle to a single ACP session, implementing [SessionPort].
 *
 * It owns a [SessionStateEngine] (the runtime) and forwards user actions to the
 * [AcpConnectionManager]. Events from the SDK are fed into the runtime via [emitEvent],
 * and a separate [events] SharedFlow allows collectors to observe all events in order.
 *
 * ## Interface contract
 * SessionBridge implements [SessionPort] so the chat layer never imports the concrete
 * type. Methods that are NOT on [SessionPort] — [emitEvent], [beginHydration],
 * [completeHydration], [failHydration], [markReady] — are called exclusively by
 * [AcpConnectionManager] (which retains a [SessionBridge] reference internally).
 *
 * ## Lifecycle
 * An internal event scope CoroutineScope backs [modelSelectionEvents] (the filtered
 * flow exposed via [SessionPort.modelSelectionEvents]). That scope is cancelled in
 * [close], which is called by [AcpSessionRegistry.clearSession] or
 * [AcpSessionRegistry.clearAll] when the session is torn down.
 */
class SessionBridge(
    override val sessionId: String,
    private val connectionManager: AcpConnectionManager?,
) : SessionPort {
    internal val runtime: SessionStateEngine = SessionRuntime(sessionId = sessionId)
    override val snapshot: StateFlow<SessionSnapshot> = runtime.snapshot

    // Replay must be large because session/load history often arrives as many chunk events
    // before ChatViewModel attaches its collector.
    private val _events =
        MutableSharedFlow<AppSessionEvent>(
            replay = 5000,
            extraBufferCapacity = 2048,
        )
    val events: SharedFlow<AppSessionEvent> = _events.asSharedFlow()

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Narrow, purpose-built flow of [AppSessionEvent.ModelSelectionConfirmed] events, exposed
     * via [SessionPort.modelSelectionEvents] so consumers can react to model changes without
     * importing the concrete bridge type.
     */
    override val modelSelectionEvents: SharedFlow<AppSessionEvent.ModelSelectionConfirmed> =
        _events
            .filterIsInstance<AppSessionEvent.ModelSelectionConfirmed>()
            .shareIn(eventScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val traceTag = "TSBridge"

    /**
     * Feeds a session event into the runtime reducer and then emits it to observers.
     *
     * Ordering matters: runtime state is updated before observers see the event.
     */
    suspend fun emitEvent(event: AppSessionEvent) {
        debug("emitEvent type=${event::class.simpleName}")
        runtime.onEvent(event)
        _events.emit(event)
    }

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
    private suspend fun sendWithRollback(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ) {
        try {
            connectionManager?.sendSessionMessage(sessionId, text, images, files)
        } catch (e: CancellationException) {
            rollbackAndRethrow(e)
        } catch (e: IllegalStateException) {
            // Missing bridge/session — surface as a failed send; the prompt was
            // optimistically shown and must be rolled back.
            rollbackAndRethrow(e)
        } catch (e: IOException) {
            // Transport-level failure — roll back the optimistic message and
            // propagate so the caller can surface the error.
            rollbackAndRethrow(e)
        }
    }

    /** Rolls back the optimistic prompt and rethrows the original failure. */
    private suspend fun rollbackAndRethrow(e: Exception): Nothing {
        runtime.onPromptSendFailed()
        throw e
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
     * Cancels the internal [eventScope], stopping the [modelSelectionEvents] shared flow.
     * Called by [AcpSessionRegistry] when the session is removed. After close, no further
     * events will be emitted through [modelSelectionEvents].
     */
    fun close() {
        eventScope.cancel()
    }

    /** Emits a debug log entry if logging is available. */
    private fun debug(message: String) {
        runCatching { android.util.Log.d(traceTag, "[$sessionId] $message") }
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
