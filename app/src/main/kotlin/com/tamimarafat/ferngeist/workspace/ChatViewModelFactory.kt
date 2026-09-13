package com.tamimarafat.ferngeist.workspace

import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.ChatScrollStateStore
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.PendingPromptStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [ChatViewModel] for a specific session, for hosting in a pane.
 *
 * Panes are not nav destinations, so `hiltViewModel()` would resolve the enclosing
 * `NavBackStackEntry`'s arguments — which are the workspace's, not this session's. The
 * handle is constructed here from the workspace selection instead.
 */
@Singleton
class ChatViewModelFactory
    @Inject
    constructor(
        private val sessionFacadeFactory: ChatSessionFacadeFactory,
        private val sessionRepository: SessionRepository,
        private val chatScrollStateStore: ChatScrollStateStore,
        private val pendingPromptStore: PendingPromptStore,
        private val recentSelectionStore: RecentSelectionStore,
        private val chatConnectionHub: ChatConnectionHub,
        private val gatewayRepository: GatewayRepository,
    ) {
        /**
         * A [ChatViewModel] reads every id out of its handle, so blank ids would build a
         * chat that silently writes its records under an empty key. Fail at the boundary
         * instead: the workspace only renders a chat pane once it has a real selection.
         */
        @Suppress("VisibleForTests")
        fun create(
            selection: WorkspaceSelection,
            mintedSessionId: String? = null,
        ): ChatViewModel {
            val serverId = selection.serverId
            val sessionId = selection.sessionId
            require(serverId.isNotBlank()) { "serverId is required to build a chat" }
            require(sessionId.isNotBlank()) { "sessionId is required to build a chat" }
            return ChatViewModel(
                sessionFacadeFactory = sessionFacadeFactory,
                sessionRepository = sessionRepository,
                chatScrollStateStore = chatScrollStateStore,
                pendingPromptStore = pendingPromptStore,
                recentSelectionStore = recentSelectionStore,
                chatConnectionHub = chatConnectionHub,
                gatewayRepository = gatewayRepository,
                // `SavedStateHandle` is public API; lint's VisibleForTests check calls the
                // constructor test-only here, so this one function suppresses it.
                savedStateHandle =
                    SavedStateHandle(
                        buildMap {
                            put("serverId", serverId)
                            put("sessionId", sessionId)
                            put("cwd", selection.cwd)
                            put("title", selection.title)
                            mintedSessionId?.let { put("mintedSessionId", it) }
                        },
                    ),
            )
        }
    }
