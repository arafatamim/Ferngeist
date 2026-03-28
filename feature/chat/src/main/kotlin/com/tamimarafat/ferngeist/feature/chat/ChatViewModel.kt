package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.tamimarafat.ferngeist.feature.chat.BuildConfig
import com.mikepenz.markdown.model.State as MarkdownRenderState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.core.common.MviViewModel
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val connectionManager: AcpConnectionManager,
    private val serverRepository: ServerRepository,
    private val sessionRepository: SessionRepository,
    private val chatScrollStateStore: ChatScrollStateStore,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<ChatState, ChatIntent, ChatEffect>(
    initialState(savedStateHandle, chatScrollStateStore)
) {
    companion object {
        private const val TRACE_TAG = "TSChatVM"

        private fun initialState(
            savedStateHandle: SavedStateHandle,
            chatScrollStateStore: ChatScrollStateStore,
        ): ChatState {
            val serverId = savedStateHandle.get<String>("serverId").orEmpty()
            val sessionId = savedStateHandle.get<String>("sessionId").orEmpty()
            return ChatState(
                restoredScrollSnapshot = if (serverId.isBlank() || sessionId.isBlank()) {
                    null
                } else {
                    chatScrollStateStore.restore(serverId, sessionId)
                }
            )
        }
    }

    private val serverId: String = savedStateHandle["serverId"] ?: error("serverId is required")
    private val sessionId: String = savedStateHandle["sessionId"] ?: error("sessionId is required")
    private val cwd: String = savedStateHandle["cwd"] ?: "/"
    private val sessionUpdatedAt: Long? = savedStateHandle.get<Long>("updatedAt")?.takeIf { it > 0L }
    private val markdownStateStore = MarkdownStateStore(
        scope = viewModelScope,
        currentMessages = { state.value.messages },
        onMarkdownStatesChanged = { markdownStates ->
            updateState {
                if (markdownStates == this.markdownStates) this
                else copy(markdownStates = markdownStates)
            }
        },
        trace = { message -> trace(message) },
    )
    private val sessionCoordinator = ChatSessionCoordinator(
        scope = viewModelScope,
        connectionManager = connectionManager,
        serverRepository = serverRepository,
        serverId = serverId,
        initialSessionId = sessionId,
        cwd = cwd,
        trace = { message -> trace(message) },
        logError = { message, error -> logError(message, error) },
        callbacks = object : ChatSessionCoordinator.Callbacks {
            override suspend fun onLoadStarted() {
                markdownStateStore.reset()
                updateState {
                    copy(
                        messages = emptyList(),
                        markdownStates = emptyMap(),
                        isLoading = true,
                        isStreaming = false,
                        isSessionReady = false,
                        canCancelStreaming = true,
                        error = null
                    )
                }
            }

            override suspend fun onSnapshot(snapshot: SessionSnapshot) {
                applySnapshot(snapshot)
            }

            override suspend fun onSessionReady() {
                updateState {
                    copy(
                        isLoading = false,
                        isSessionReady = true,
                        canCancelStreaming = true,
                        error = null
                    )
                }
            }

            override suspend fun onSessionStored(sessionId: String, cwd: String, updatedAt: Long) {
                sessionRepository.upsertSession(
                    serverId = serverId,
                    summary = SessionSummary(
                        id = sessionId,
                        cwd = cwd,
                        updatedAt = updatedAt,
                    ),
                )
            }

            override suspend fun onLoadFailed(message: String) {
                updateState {
                    copy(
                        isLoading = false,
                        isSessionReady = false,
                        error = message
                    )
                }
                emitEffect(ChatEffect.ShowError(message))
            }

            override suspend fun onOperationError(message: String, stopStreaming: Boolean) {
                if (stopStreaming) {
                    updateState {
                        copy(
                            isStreaming = false,
                            error = message
                        )
                    }
                }
                emitEffect(ChatEffect.ShowError(message))
            }

            override suspend fun onStreamingCancelled() {
                updateState { copy(isStreaming = false) }
            }

            override suspend fun onCancelUnsupported() {
                updateState { copy(canCancelStreaming = false) }
                emitEffect(ChatEffect.ShowMessage("Cancel is not supported by this server"))
            }

            override suspend fun onModelUpdated() {
                emitEffect(ChatEffect.ShowMessage("Model updated"))
            }

            override suspend fun onCapabilitiesChanged(capabilities: AcpAgentCapabilities) {
                updateState {
                    copy(
                        canSendImages = capabilities.prompt.image,
                        supportsEmbeddedContext = capabilities.prompt.embeddedContext,
                    )
                }
            }
        },
    )

    init {
        viewModelScope.launch {
            sessionCoordinator.loadSession()
        }
        viewModelScope.launch {
            connectionManager.connectionState.collect { connectionState ->
                updateState {
                    copy(
                        connectionState = connectionState,
                        isSessionReady = if (
                            connectionState is AcpConnectionState.Connected
                        ) {
                            isSessionReady
                        } else {
                            false
                        },
                        isStreaming = if (
                            connectionState is AcpConnectionState.Connected
                        ) {
                            isStreaming
                        } else {
                            false
                        },
                    )
                }
                sessionCoordinator.onConnectionStateChanged(connectionState)
            }
        }
        viewModelScope.launch {
            connectionManager.diagnostics.collect { diagnostics ->
                updateState { copy(connectionDiagnostics = diagnostics) }
            }
        }
    }

    private suspend fun applySnapshot(snapshot: SessionSnapshot) {
        val markdownProjection = markdownStateStore.onSnapshot(
            messages = snapshot.messages,
            loadState = snapshot.loadState,
        )
        updateState {
            val failed = snapshot.loadState == SessionLoadState.FAILED
            copy(
                messages = snapshot.messages,
                markdownStates = markdownProjection.markdownStates,
                isStreaming = snapshot.isStreaming,
                usage = snapshot.toUsageState(),
                availableCommands = snapshot.availableCommands,
                commandsAdvertised = snapshot.commandsAdvertised,
                configOptions = snapshot.configOptions,
                isLoading = snapshot.loadState == SessionLoadState.HYDRATING ||
                    markdownProjection.pendingInitialHydration,
                isSessionReady = snapshot.loadState == SessionLoadState.READY &&
                    !markdownProjection.pendingInitialHydration,
                error = if (failed) {
                    snapshot.error ?: "Could not load this session. Check connection and retry."
                } else {
                    null
                }
            )
        }
    }

    private fun SessionSnapshot.toUsageState(): UsageState? {
        val usage = usage ?: return null
        return UsageState(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
            cachedReadTokens = usage.cachedReadTokens,
            contextWindowTokens = usage.contextWindowTokens,
            costUsd = usage.costUsd,
        )
    }

    override fun onCleared() {
        markdownStateStore.reset()
        sessionCoordinator.clear()
        super.onCleared()
    }

    override suspend fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage -> sessionCoordinator.sendMessage(intent.text, intent.images)
            is ChatIntent.CancelStreaming -> sessionCoordinator.cancelStreaming()
            is ChatIntent.SetConfigOption -> sessionCoordinator.setConfigOption(intent.optionId, intent.value)
            is ChatIntent.GrantPermission -> sessionCoordinator.grantPermission(intent.toolCallId, intent.optionId)
            is ChatIntent.DenyPermission -> sessionCoordinator.denyPermission(intent.toolCallId)
            is ChatIntent.RetryLoad -> sessionCoordinator.loadSession()
        }
    }

    fun persistScrollSnapshot(snapshot: ChatScrollSnapshot) {
        chatScrollStateStore.save(serverId, sessionId, snapshot)
        updateState {
            if (restoredScrollSnapshot == snapshot) this
            else copy(restoredScrollSnapshot = snapshot)
        }
    }

    private fun trace(message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching { Log.d(TRACE_TAG, message) }
    }

    private fun logError(message: String, error: Throwable? = null) {
        runCatching {
            if (error == null) {
                Log.e("ChatViewModel", message)
            } else {
                Log.e("ChatViewModel", message, error)
            }
        }
    }

}
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val markdownStates: Map<String, MarkdownRenderState> = emptyMap(),
    val restoredScrollSnapshot: ChatScrollSnapshot? = null,
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val isSessionReady: Boolean = false,
    val canCancelStreaming: Boolean = true,
    val connectionState: AcpConnectionState = AcpConnectionState.Disconnected,
    val connectionDiagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    val configOptions: List<SessionConfigOption> = emptyList(),
    val usage: UsageState? = null,
    val availableCommands: List<String> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val canSendImages: Boolean = false,
    val supportsEmbeddedContext: Boolean = false,
    val error: String? = null,
)

data class UsageState(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedReadTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val costUsd: Double? = null,
)

sealed interface ChatIntent {
    data class SendMessage(val text: String, val images: List<ChatImageData> = emptyList()) : ChatIntent
    data object CancelStreaming : ChatIntent
    data class SetConfigOption(val optionId: String, val value: SessionConfigValue) : ChatIntent
    data class GrantPermission(val toolCallId: String, val optionId: String) : ChatIntent
    data class DenyPermission(val toolCallId: String) : ChatIntent
    data object RetryLoad : ChatIntent
}

sealed interface ChatEffect {
    data class ShowError(val message: String) : ChatEffect
    data class ShowMessage(val message: String) : ChatEffect
    data object NavigateBack : ChatEffect
}
