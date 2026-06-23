package com.tamimarafat.ferngeist.feature.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.common.MviViewModel
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.ActiveChat
import com.tamimarafat.ferngeist.core.model.store.ActiveChatStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mikepenz.markdown.model.State as MarkdownRenderState

/**
 * Orchestrates chat UI state by binding the session facade, scroll state, and markdown parsing.
 *
 * The view model holds no ACP details; all transport-specific logic is delegated to the facade.
 */
@HiltViewModel
class ChatViewModel
    @Inject
    constructor(
        sessionFacadeFactory: ChatSessionFacadeFactory,
        private val sessionRepository: SessionRepository,
        private val chatScrollStateStore: ChatScrollStateStore,
        val recentSelectionStore: RecentSelectionStore,
        private val activeChatStore: ActiveChatStore,
        savedStateHandle: SavedStateHandle,
    ) : MviViewModel<ChatState, ChatIntent, ChatEffect>(initialChatState()) {

        companion object {
            private const val TRACE_TAG = "TSChatVM"

            private fun initialChatState(): ChatState = ChatState()
        }

        private val serverId: String = savedStateHandle["serverId"] ?: error("serverId is required")
        private val sessionId: String = savedStateHandle["sessionId"] ?: error("sessionId is required")
        private val cwd: String = savedStateHandle["cwd"] ?: "/"
        private val sessionUpdatedAt: Long? = savedStateHandle.get<Long>("updatedAt")?.takeIf { it > 0L }
        private val sessionTitle: String =
            savedStateHandle.get<String>("title")?.let { Uri.decode(it) }.orEmpty()
        private val gatewayId: String? = savedStateHandle.get<String>("gatewayId")?.let { Uri.decode(it) }
        private val sessionFacade: ChatSessionFacade =
            sessionFacadeFactory.create(
                scope = viewModelScope,
                serverId = serverId,
                sessionId = sessionId,
                cwd = cwd,
            )
        private val markdownStateStore =
            MarkdownStateStore(
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
        private val sessionCoordinator =
            ChatSessionCoordinator(
                scope = viewModelScope,
                facade = sessionFacade,
                callbacks =
                    object : ChatSessionCoordinator.Callbacks {
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
                                    error = null,
                                )
                            }
                        }

                        override suspend fun onSnapshot(snapshot: ChatSessionSnapshot) {
                            applySnapshot(snapshot)
                        }

                        override suspend fun onSessionReady() {
                            updateState {
                                copy(
                                    isLoading = false,
                                    isSessionReady = true,
                                    canCancelStreaming = true,
                                    error = null,
                                )
                            }
                        }

                        override suspend fun onSessionStored(
                            sessionId: String,
                            cwd: String,
                            updatedAt: Long,
                        ) {
                            sessionRepository.upsertSession(
                                serverId = serverId,
                                summary =
                                    SessionSummary(
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
                                    error = message,
                                )
                            }
                            emitEffect(ChatEffect.ShowError(message))
                        }

                        override suspend fun onOperationError(
                            message: String,
                            stopStreaming: Boolean,
                        ) {
                            if (stopStreaming) {
                                updateState {
                                    copy(
                                        isStreaming = false,
                                        error = message,
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

                        override suspend fun onCapabilitiesChanged(capabilities: ChatAgentCapabilities) {
                            updateState {
                                copy(
                                    canSendImages = capabilities.canSendImages,
                                    supportsEmbeddedContext = capabilities.supportsEmbeddedContext,
                                )
                            }
                        }
                    },
            )

        init {
            updateState { copy(serverId = serverId) }
            // Record this as the active chat so the connection notification deep-links
            // back here instead of dropping the user on the home screen.
            activeChatStore.setActiveChat(
                ActiveChat(
                    serverId = serverId,
                    sessionId = sessionId,
                    cwd = cwd,
                    title = sessionTitle,
                    gatewayId = gatewayId,
                ),
            )
            resolveSessionTitle()
            viewModelScope.launch {
                val snapshot = chatScrollStateStore.restore(serverId, sessionId)
                updateState { copy(restoredScrollSnapshot = snapshot) }
            }
            viewModelScope.launch {
                sessionCoordinator.loadSession()
            }
            viewModelScope.launch {
                sessionFacade.connectionState.collect { connectionState ->
                    updateState {
                        copy(
                            connectionState = connectionState,
                            isSessionReady =
                                if (connectionState is ChatConnectionState.Connected) {
                                    isSessionReady
                                } else {
                                    false
                                },
                            isStreaming =
                                if (connectionState is ChatConnectionState.Connected) {
                                    isStreaming
                                } else {
                                    false
                                },
                        )
                    }
                }
            }
            viewModelScope.launch {
                sessionFacade.diagnostics.collect { diagnostics ->
                    updateState { copy(connectionDiagnostics = diagnostics) }
                }
            }
        }

        /**
         * Populates [ChatState.title] for the app bar. The deep-link path (push
         * notification tap) can't carry the real session name, so the nav-arg
         * `title` is blank and we look it up from the local session store. When the
         * nav arg already has a title (session list → chat) we use that and skip the
         * lookup to avoid a stale read.
         */
        private fun resolveSessionTitle() {
            if (sessionTitle.isNotBlank()) {
                updateState { copy(title = sessionTitle) }
                return
            }
            viewModelScope.launch {
                val resolved = sessionRepository.getSession(serverId, sessionId)?.title
                if (resolved != null) {
                    updateState { copy(title = resolved) }
                    activeChatStore.setActiveChat(
                        ActiveChat(
                            serverId = serverId,
                            sessionId = sessionId,
                            cwd = cwd,
                            title = resolved,
                            gatewayId = gatewayId,
                        ),
                    )
                }
            }
        }

        /**
         * Applies a snapshot from the facade to UI state, keeping markdown hydration in sync.
         */
        private suspend fun applySnapshot(snapshot: ChatSessionSnapshot) {
            val markdownProjection =
                markdownStateStore.onSnapshot(
                    messages = snapshot.messages,
                    loadState = snapshot.loadState,
                )
            updateState {
                val failed = snapshot.loadState == ChatLoadState.FAILED
                copy(
                    messages = snapshot.messages,
                    markdownStates = markdownProjection.markdownStates,
                    isStreaming = snapshot.isStreaming,
                    usage = snapshot.usage,
                    availableCommands = snapshot.availableCommands,
                    commandsAdvertised = snapshot.commandsAdvertised,
                    configOptions = snapshot.configOptions,
                    // Loading ends only after session state is ready *and* markdown has hydrated.
                    isLoading =
                        snapshot.loadState == ChatLoadState.HYDRATING ||
                            markdownProjection.pendingInitialHydration,
                    isSessionReady =
                        snapshot.loadState == ChatLoadState.READY &&
                            !markdownProjection.pendingInitialHydration,
                    error =
                        if (failed) {
                            snapshot.error ?: "Could not load this session. Check connection and retry."
                        } else {
                            null
                        },
                )
            }
        }

        override fun onCleared() {
            markdownStateStore.reset()
            sessionCoordinator.clear()
            // Drop the active-chat record when the user leaves this chat, so push
            // suppression keys off the session actually on screen rather than the last
            // one opened. clearIfCurrent() no-ops if another chat is already active.
            activeChatStore.clearIfCurrent(sessionId)
            super.onCleared()
        }

        /**
         * Routes UI intents to the coordinator so ACP details stay outside the view model.
         */
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

        /**
         * Persists and mirrors the latest scroll snapshot for restore on re-entry.
         */
        fun persistScrollSnapshot(snapshot: ChatScrollSnapshot) {
            viewModelScope.launch {
                chatScrollStateStore.save(serverId, sessionId, snapshot)
            }
            updateState {
                if (restoredScrollSnapshot == snapshot) this
                else copy(restoredScrollSnapshot = snapshot)
            }
        }

        /** Debug-only trace logger. */
        private fun trace(message: String) {
            if (!BuildConfig.DEBUG) return
            runCatching { Log.d(TRACE_TAG, message) }
        }


    }

/** UI state for the chat screen. */
data class ChatState(
    val serverId: String = "",
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val markdownStates: Map<String, MarkdownRenderState> = emptyMap(),
    val restoredScrollSnapshot: ChatScrollSnapshot? = null,
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val isSessionReady: Boolean = false,
    val canCancelStreaming: Boolean = true,
    val connectionState: ChatConnectionState = ChatConnectionState.Disconnected,
    val connectionDiagnostics: ChatConnectionDiagnostics = ChatConnectionDiagnostics(),
    val configOptions: List<ChatConfigOption> = emptyList(),
    val usage: UsageState? = null,
    val availableCommands: List<ChatCommand> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val canSendImages: Boolean = false,
    val supportsEmbeddedContext: Boolean = false,
    val error: String? = null,
)

/** User intents emitted from the chat UI. */
sealed interface ChatIntent {
    data class SendMessage(
        val text: String,
        val images: List<ChatImageData> = emptyList(),
    ) : ChatIntent

    data object CancelStreaming : ChatIntent

    data class SetConfigOption(
        val optionId: String,
        val value: ChatConfigValue,
    ) : ChatIntent

    data class GrantPermission(
        val toolCallId: String,
        val optionId: String,
    ) : ChatIntent

    data class DenyPermission(
        val toolCallId: String,
    ) : ChatIntent

    data object RetryLoad : ChatIntent
}

/** One-shot effects emitted to the UI layer (snackbar, navigation, etc.). */
sealed interface ChatEffect {
    data class ShowError(val message: String) : ChatEffect
    data class ShowMessage(val message: String) : ChatEffect
    data object NavigateBack : ChatEffect
}
