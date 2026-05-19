package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonElement
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SessionBridge is the UI-facing handle to a single ACP session.
 *
 * It owns a [SessionRuntime] (state engine) and forwards user actions to the
 * [AcpConnectionManager]. Events from the SDK are fed into the runtime via [emitEvent],
 * and a separate [events] SharedFlow allows collectors to observe all events in order.
 *
 * The bridge does not directly implement any session interface yet; that is the next
 * architectural step (SessionPort). For now, it serves as the adapter between transport
 * (AcpConnectionManager) and state (SessionRuntime).
 */
class SessionBridge(
    val sessionId: String,
    private val connectionManager: AcpConnectionManager?,
) {
    private val runtime = SessionRuntime(sessionId = sessionId)
    val snapshot: StateFlow<SessionSnapshot> = runtime.snapshot

    // Replay must be large because session/load history often arrives as many chunk events
    // before ChatViewModel attaches its collector.
    private val _events =
        MutableSharedFlow<AppSessionEvent>(
            replay = 5000,
            extraBufferCapacity = 2048,
        )
    val events: SharedFlow<AppSessionEvent> = _events.asSharedFlow()
    private val traceTag = "TSBridge"

    suspend fun emitEvent(event: AppSessionEvent) {
        debug("emitEvent type=${event::class.simpleName}")
        runtime.onEvent(event)
        _events.emit(event)
    }

    suspend fun beginHydration() {
        runtime.beginHydration()
    }

    suspend fun completeHydration() {
        runtime.completeHydration()
    }

    suspend fun failHydration(error: String?) {
        runtime.failHydration(error)
    }

    suspend fun markReady() {
        runtime.markReady()
    }

    suspend fun sendPrompt(
        text: String,
        images: List<Pair<String, String>> = emptyList(),
    ) {
        runtime.onLocalPromptStarted(text, images)
        try {
            connectionManager?.sendSessionMessage(sessionId, text, images)
        } catch (t: Throwable) {
            runtime.onPromptSendFailed()
            throw t
        }
    }

    suspend fun cancel() {
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
    suspend fun setConfigOption(
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

    suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        connectionManager?.respondPermissionSelected(sessionId, toolCallId, optionId)
    }

    suspend fun denyPermission(toolCallId: String) {
        connectionManager?.respondPermissionCancelled(sessionId, toolCallId)
    }

    fun close() {
        // Intentionally empty: SessionBridge doesn't directly own resources that need
        // explicit synchronous teardown. SDK sessions and transport resources are
        // managed by AcpConnectionManager / AcpTransportClient and removed via
        // AcpSessionRegistry.clearSession. This method exists as a lifecycle hook
        // so callers (for example AcpSessionRegistry) may invoke it if future
        // cleanup is required.
    }

    private fun debug(message: String) {
        runCatching { android.util.Log.d(traceTag, "[$sessionId] $message") }
    }
}

sealed interface AppSessionEvent {
    data class UserMessage(
        val text: String,
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