package com.tamimarafat.ferngeist.workspace

import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionPresence
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.ChatListings
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.TurnTimingRepository
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.ChatScrollStateStore
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.PendingPromptStore
import com.tamimarafat.ferngeist.feature.chat.SwitcherHintStore
import com.tamimarafat.ferngeist.feature.chat.deps.ChatLocalStores
import com.tamimarafat.ferngeist.feature.chat.deps.ChatRepositories
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/** The session-side collaborators a [ChatViewModel] is built from, grouped to keep the factory's constructor short. */
data class ChatSessionServices
    @Inject
    constructor(
        val sessionFacadeFactory: ChatSessionFacadeFactory,
        val sessionRepository: ChatCatalog,
        val presence: ChatSessionPresence,
        val launchableTargetRepository: LaunchableTargetRepository,
        val turnTimingRepository: TurnTimingRepository,
        val chatListings: ChatListings,
        val providerQuotas: com.tamimarafat.ferngeist.core.model.repository.ProviderQuotas,
    )

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
        private val services: ChatSessionServices,
        private val chatScrollStateStore: ChatScrollStateStore,
        private val pendingPromptStore: PendingPromptStore,
        private val switcherHintStore: SwitcherHintStore,
        private val recentSelectionStore: RecentSelectionStore,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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
                sessionFacadeFactory = services.sessionFacadeFactory,
                presence = services.presence,
                repositories =
                    ChatRepositories(
                        sessionRepository = services.sessionRepository,
                        launchableTargetRepository = services.launchableTargetRepository,
                        turnTimingRepository = services.turnTimingRepository,
                        chatListings = services.chatListings,
                        providerQuotas = services.providerQuotas,
                    ),
                stores =
                    ChatLocalStores(
                        scrollState = chatScrollStateStore,
                        pendingPrompts = pendingPromptStore,
                        switcherHints = switcherHintStore,
                        recentSelections = recentSelectionStore,
                    ),
                ioDispatcher = ioDispatcher,
                // `SavedStateHandle` is public API; lint's VisibleForTests check calls the
                // constructor test-only here, so this one function suppresses it.
                savedStateHandle =
                    SavedStateHandle(
                        buildMap {
                            put("serverId", serverId)
                            put("sessionId", sessionId)
                            put("cwd", selection.cwd)
                            put("title", selection.title)
                            put("updatedAt", selection.updatedAt ?: -1L)
                            mintedSessionId?.let { put("mintedSessionId", it) }
                        },
                    ),
            )
        }
    }
