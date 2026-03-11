package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

class SessionBridge(
    val sessionId: String,
    private val connectionManager: AcpConnectionManager?,
) {
    private val runtime = SessionRuntime(sessionId = sessionId)
    val snapshot: StateFlow<SessionSnapshot> = runtime.snapshot

    // Replay must be large because session/load history often arrives as many chunk events
    // before ChatViewModel attaches its collector.
    private val _events = MutableSharedFlow<AppSessionEvent>(
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

    suspend fun sendPrompt(text: String, images: List<Pair<String, String>> = emptyList()) {
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

    suspend fun setConfigOption(optionId: String, value: SessionConfigValue) {
        val option = snapshot.value.configOptions.firstOrNull { it.id == optionId }
        when (option?.origin) {
            SessionConfigOrigin.LegacyMode -> {
                val modeId = (value as? SessionConfigValue.StringValue)?.value ?: return
                connectionManager?.setSessionMode(sessionId, modeId)
                runtime.onEvent(AppSessionEvent.ModeChanged(modeId))
            }

            SessionConfigOrigin.LegacyModel -> {
                val modelId = (value as? SessionConfigValue.StringValue)?.value ?: return
                // Stand-in for missing SDK client event support: legacy session/set_model does
                // not surface a first-class CurrentModelUpdate notification to the client, so
                // Ferngeist confirms the selection optimistically and lets config_option_update
                // become the source of truth when the agent exposes model via config options.
                connectionManager?.setSessionModel(sessionId, modelId)
                runtime.onEvent(AppSessionEvent.ModelSelectionConfirmed(modelId))
            }

            else -> {
                connectionManager?.setSessionConfigOption(sessionId, optionId, value)
                runtime.onEvent(
                    AppSessionEvent.ConfigOptionValueChanged(
                        optionId = optionId,
                        value = value,
                    )
                )
            }
        }
    }

    suspend fun grantPermission(toolCallId: String, optionId: String) {
        connectionManager?.respondPermissionSelected(sessionId, toolCallId, optionId)
    }

    suspend fun denyPermission(toolCallId: String) {
        connectionManager?.respondPermissionCancelled(sessionId, toolCallId)
    }

    fun close() {
        // Clean up
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
        val kind: String?,
        val status: String?,
    ) : AppSessionEvent
    data class ToolCallUpdated(
        val toolCallId: String,
        val status: String?,
        val title: String?,
        val kind: String?,
        val output: String?,
        val rawOutput: String? = null,
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
    data class ModeChanged(val modeId: String) : AppSessionEvent
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
        val content: String,
        val timestampMs: Long? = null,
    ) : AppSessionEvent
    data class UsageUpdated(
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val cachedReadTokens: Int? = null,
        val contextWindowTokens: Int? = null,
        val costUsd: Double? = null,
    ) : AppSessionEvent
    data class CommandsUpdated(val commands: List<String>) : AppSessionEvent
    data class SessionInfoUpdated(
        val title: String?,
        val updatedAt: String?,
    ) : AppSessionEvent
    /** Synthetic event emitted after session/load replay events have been forwarded. */
    data object SessionLoadComplete : AppSessionEvent
    data class TurnComplete(val stopReason: String) : AppSessionEvent
    data class Unknown(val raw: String) : AppSessionEvent
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
