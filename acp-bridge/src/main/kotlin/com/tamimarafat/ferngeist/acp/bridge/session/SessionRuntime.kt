package com.tamimarafat.ferngeist.acp.bridge.session

import android.util.Log
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns canonical transcript/session state for one ACP session.
 * All mutations are serialized through [mutex] so event ordering is deterministic.
 */
class SessionRuntime(
    private val sessionId: String,
) {
    companion object {
        private const val TAG = "TSRuntime"
    }

    private data class RuntimeData(
        val messages: List<ChatMessage> = emptyList(),
        val isStreaming: Boolean = false,
        val usage: SessionUsage? = null,
        val availableCommands: List<String> = emptyList(),
        val commandsAdvertised: Boolean = false,
        val availableModes: List<SessionMode> = emptyList(),
        val currentModeId: String? = null,
        val configOptions: List<SessionConfigOption> = emptyList(),
    )

    private val mutex = Mutex()
    private var live = RuntimeData()
    private var buffered = RuntimeData()
    private var eventSeq = 0L

    private val _snapshot = MutableStateFlow(
        SessionSnapshot(
            sessionId = sessionId,
            loadState = SessionLoadState.READY,
        )
    )
    val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    suspend fun beginHydration() {
        mutex.withLock {
            debug("beginHydration: clearing buffered/live snapshot view")
            buffered = RuntimeData()
            _snapshot.value = _snapshot.value.copy(
                loadState = SessionLoadState.HYDRATING,
                messages = emptyList(),
                isStreaming = false,
                usage = null,
                availableCommands = emptyList(),
                commandsAdvertised = false,
                error = null,
            )
        }
    }

    suspend fun completeHydration() {
        mutex.withLock {
            debug(
                "completeHydration: committing buffered messages=${buffered.messages.size}, " +
                    "bufferedStreaming=${buffered.isStreaming}"
            )
            live = buffered.copy(
                messages = SessionMessageReducer.finishStreaming(buffered.messages),
                isStreaming = false,
            )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun failHydration(error: String?) {
        mutex.withLock {
            debug("failHydration: error=${error ?: "unknown"}")
            _snapshot.value = _snapshot.value.copy(
                loadState = SessionLoadState.FAILED,
                error = error,
                isStreaming = false,
            )
        }
    }

    suspend fun markReady() {
        mutex.withLock {
            if (_snapshot.value.loadState == SessionLoadState.HYDRATING) {
                debug("markReady: ignored while HYDRATING")
                return@withLock
            }
            debug("markReady: publishing READY with liveMessages=${live.messages.size}")
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun onEvent(event: AppSessionEvent) {
        mutex.withLock {
            val seq = ++eventSeq
            val loadState = _snapshot.value.loadState
            debug(
                "event#$seq state=$loadState type=${event::class.simpleName} " +
                    "summary=${summarizeEvent(event)}"
            )
            if (_snapshot.value.loadState == SessionLoadState.HYDRATING) {
                buffered = reduce(buffered, event)
                debug(
                    "event#$seq bufferedApplied messages=${buffered.messages.size} " +
                        "streaming=${buffered.isStreaming}"
                )
                return@withLock
            }
            live = reduce(live, event)
            debug("event#$seq liveApplied messages=${live.messages.size} streaming=${live.isStreaming}")
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun onLocalPromptStarted(text: String, images: List<Pair<String, String>>) {
        mutex.withLock {
            if (_snapshot.value.loadState != SessionLoadState.READY) {
                debug("onLocalPromptStarted ignored: loadState=${_snapshot.value.loadState}")
                return@withLock
            }

            val mappedImages = images.map { (base64, mimeType) ->
                ChatImageData(base64 = base64, mimeType = mimeType)
            }
            debug("onLocalPromptStarted textLen=${text.length}, images=${mappedImages.size}")
            val withUser = SessionMessageReducer.appendLocalUserMessage(live.messages, text, mappedImages)
            val withAssistantPlaceholder = SessionMessageReducer.startStreaming(withUser)

            live = live.copy(
                messages = withAssistantPlaceholder,
                isStreaming = true,
            )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun onPromptSendFailed() {
        mutex.withLock {
            debug("onPromptSendFailed: finishing local streaming placeholder")
            live = live.copy(
                messages = SessionMessageReducer.finishStreaming(live.messages),
                isStreaming = false,
            )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    suspend fun onLocalCancel() {
        mutex.withLock {
            debug("onLocalCancel: finishing local streaming placeholder")
            live = live.copy(
                messages = SessionMessageReducer.finishStreaming(live.messages),
                isStreaming = false,
            )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    private fun reduce(current: RuntimeData, event: AppSessionEvent): RuntimeData {
        var messages = current.messages
        var isStreaming = current.isStreaming
        var usage = current.usage
        var availableCommands = current.availableCommands
        var commandsAdvertised = current.commandsAdvertised
        var availableModes = current.availableModes
        var currentModeId = current.currentModeId
        var configOptions = current.configOptions

        messages = when (event) {
            is AppSessionEvent.SessionLoadComplete -> SessionMessageReducer.finishStreaming(messages)
            else -> SessionMessageReducer.handleEvent(messages, event)
        }

        when (event) {
            is AppSessionEvent.TurnComplete -> isStreaming = false
            is AppSessionEvent.SessionLoadComplete -> isStreaming = false
            is AppSessionEvent.UsageUpdated -> {
                usage = SessionUsage(
                    promptTokens = event.promptTokens,
                    completionTokens = event.completionTokens,
                    totalTokens = event.totalTokens,
                    cachedReadTokens = event.cachedReadTokens,
                    contextWindowTokens = event.contextWindowTokens,
                    costUsd = event.costUsd,
                )
            }
            is AppSessionEvent.CommandsUpdated -> {
                availableCommands = event.commands
                commandsAdvertised = true
            }
            is AppSessionEvent.ModeChanged -> {
                currentModeId = event.modeId
            }
            is AppSessionEvent.ModesUpdated -> {
                availableModes = event.modes
                if (!event.currentModeId.isNullOrBlank()) {
                    currentModeId = event.currentModeId
                }
            }
            is AppSessionEvent.ConfigOptionsUpdated -> {
                val existingById = configOptions.associateBy { it.id }.toMutableMap()
                event.options.forEach { option -> existingById[option.id] = option }
                configOptions = existingById.values.toList()
            }
            is AppSessionEvent.ModelSelectionConfirmed -> {
                val selectedModel = event.modelId
                if (!selectedModel.isNullOrBlank()) {
                    configOptions = configOptions.map { option ->
                        if (option.id == "model") option.copy(currentValue = selectedModel) else option
                    }
                }
            }
            else -> Unit
        }

        val derivedStreaming = isStreaming || messages.any { it.isStreaming }
        return current.copy(
            messages = messages,
            isStreaming = derivedStreaming,
            usage = usage,
            availableCommands = availableCommands,
            commandsAdvertised = commandsAdvertised,
            availableModes = availableModes,
            currentModeId = currentModeId,
            configOptions = configOptions,
        )
    }

    private fun publishLive(loadState: SessionLoadState, error: String?) {
        val last = live.messages.lastOrNull()
        val lastRole = last?.role?.name ?: "none"
        val lastLen = last?.content?.length ?: 0
        val lastSegments = last?.segments?.size ?: 0
        _snapshot.value = SessionSnapshot(
            sessionId = sessionId,
            loadState = loadState,
            messages = live.messages,
            isStreaming = live.isStreaming,
            usage = live.usage,
            availableCommands = live.availableCommands,
            commandsAdvertised = live.commandsAdvertised,
            availableModes = live.availableModes,
            currentModeId = live.currentModeId,
            configOptions = live.configOptions,
            error = error,
        )
        debug(
            "publishLive state=$loadState messages=${live.messages.size} streaming=${live.isStreaming} " +
                "lastRole=$lastRole lastLen=$lastLen lastSegments=$lastSegments " +
                "commands=${live.availableCommands.size} modes=${live.availableModes.size}"
        )
    }

    private fun summarizeEvent(event: AppSessionEvent): String {
        return when (event) {
            is AppSessionEvent.UserMessage -> "userLen=${event.text.length} append=${event.append}"
            is AppSessionEvent.AgentMessage -> "agentLen=${event.text.length}"
            is AppSessionEvent.AgentThought -> "thoughtLen=${event.text.length}"
            is AppSessionEvent.ToolCallStarted -> "toolCallId=${event.toolCallId} status=${event.status}"
            is AppSessionEvent.ToolCallUpdated -> "toolCallId=${event.toolCallId} status=${event.status}"
            is AppSessionEvent.ToolPermissionRequested -> "toolCallId=${event.toolCallId} options=${event.options.size}"
            is AppSessionEvent.ToolPermissionResolved -> "toolCallId=${event.toolCallId}"
            is AppSessionEvent.ModeChanged -> "modeId=${event.modeId}"
            is AppSessionEvent.ModesUpdated -> "modes=${event.modes.size} current=${event.currentModeId}"
            is AppSessionEvent.ConfigOptionsUpdated -> "configOptions=${event.options.size}"
            is AppSessionEvent.ModelSelectionConfirmed -> "modelId=${event.modelId}"
            is AppSessionEvent.PlanUpdated -> "planLen=${event.content.length}"
            is AppSessionEvent.UsageUpdated -> "usageTotal=${event.totalTokens} context=${event.contextWindowTokens}"
            is AppSessionEvent.CommandsUpdated -> "commands=${event.commands.size}"
            is AppSessionEvent.SessionInfoUpdated -> "title=${event.title} updatedAt=${event.updatedAt}"
            is AppSessionEvent.SessionLoadComplete -> "sessionLoadComplete"
            is AppSessionEvent.TurnComplete -> "stopReason=${event.stopReason}"
            is AppSessionEvent.Unknown -> "unknownRawLen=${event.raw.length}"
        }
    }

    private fun debug(message: String) {
        Log.d(TAG, "[$sessionId] $message")
    }
}
