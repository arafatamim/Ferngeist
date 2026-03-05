package com.tamimarafat.ferngeist.acp.bridge.session

import android.util.Log
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SessionBridge(
    val sessionId: String,
    private val connectionManager: AcpConnectionManager?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val runtime = SessionRuntime(sessionId = sessionId)
    val snapshot: StateFlow<SessionSnapshot> = runtime.snapshot

    private val _currentModeId = MutableSharedFlow<String?>(replay = 1)
    val currentModeId: SharedFlow<String?> = _currentModeId.asSharedFlow()
    private val _availableModes = MutableStateFlow<List<SessionMode>>(emptyList())
    val availableModes: StateFlow<List<SessionMode>> = _availableModes.asStateFlow()
    private val _configOptions = MutableStateFlow<List<SessionConfigOption>>(emptyList())
    val configOptions: StateFlow<List<SessionConfigOption>> = _configOptions.asStateFlow()

    // Replay must be large because session/load history often arrives as many chunk events
    // before ChatViewModel attaches its collector.
    private val _events = MutableSharedFlow<AppSessionEvent>(
        replay = 5000,
        extraBufferCapacity = 2048,
    )
    val events: SharedFlow<AppSessionEvent> = _events.asSharedFlow()
    private val traceTag = "TSBridge"

    suspend fun emitEvent(event: AppSessionEvent) {
        Log.d(traceTag, "[$sessionId] emitEvent type=${event::class.simpleName}")
        when (event) {
            is AppSessionEvent.ModeChanged -> _currentModeId.emit(event.modeId)
            is AppSessionEvent.ModesUpdated -> {
                _availableModes.value = event.modes
                event.currentModeId?.let { _currentModeId.emit(it) }
            }
            is AppSessionEvent.ConfigOptionsUpdated -> {
                val existingById = _configOptions.value.associateBy { it.id }.toMutableMap()
                event.options.forEach { option ->
                    existingById[option.id] = option
                }
                _configOptions.value = existingById.values.toList()
            }
            else -> Unit
        }
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

    suspend fun setMode(modeId: String) {
        connectionManager?.setSessionMode(sessionId, modeId)
        _currentModeId.emit(modeId)
        runtime.onEvent(AppSessionEvent.ModeChanged(modeId))
    }

    suspend fun setConfigOption(optionId: String, value: String) {
        connectionManager?.setSessionConfigOption(sessionId, optionId, value)
        applyConfigValue(optionId, value)
        runtime.onEvent(
            AppSessionEvent.ConfigOptionsUpdated(
                options = _configOptions.value
            )
        )
    }

    suspend fun setModel(modelId: String) {
        connectionManager?.setSessionModel(sessionId, modelId)
        applyConfigValue("model", modelId)
        runtime.onEvent(AppSessionEvent.ModelSelectionConfirmed(modelId))
    }

    private fun applyConfigValue(optionId: String, value: String) {
        _configOptions.value = _configOptions.value.map { option ->
            if (option.id == optionId) option.copy(currentValue = value) else option
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

data class SessionConfigOption(
    val id: String,
    val name: String,
    val description: String? = null,
    val kind: String? = null,
    val currentValue: String? = null,
    val options: List<SessionConfigChoice> = emptyList(),
)

data class SessionConfigChoice(
    val id: String,
    val label: String,
    val value: String,
    val description: String? = null,
)
