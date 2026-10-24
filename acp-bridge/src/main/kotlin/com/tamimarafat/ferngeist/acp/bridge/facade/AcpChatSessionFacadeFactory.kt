package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionSurface
import com.tamimarafat.ferngeist.acp.bridge.hub.chatIdFor
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * ACP-backed implementation of [ChatSessionFacadeFactory].
 *
 * Keeps ACP wiring outside the feature layer while still allowing the
 * ViewModel to create a per-session facade using runtime parameters. Chat
 * managers are acquired from the shared hub (a [ChatConnectionSurface]
 * implemented by [com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub]),
 * which owns their lifetime: a returning screen reuses the live transport
 * instead of reconnecting, so backgrounded chats keep streaming (hot cap 3,
 * never evicting a streaming chat). The hub tears managers down on eviction
 * or explicit close.
 */
class AcpChatSessionFacadeFactory(
    private val hub: ChatConnectionSurface,
    launchableTargetRepository: LaunchableTargetRepository,
    gatewaySourceRepository: GatewaySourceRepository,
    gatewayRepository: GatewayRepository,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatSessionFacadeFactory {
    private val repositories =
        LaunchRepositories(
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            launchableTargetRepository = launchableTargetRepository,
            ioDispatcher = ioDispatcher,
        )

    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade {
        val chatId = chatIdFor(serverId, sessionId)
        val existing = hub.managerFor(chatId)
        val manager = existing ?: hub.acquireChatManager()
        return AcpChatSessionFacade(
            scope = scope,
            connectionManager = manager,
            repositories = repositories,
            opening =
                ChatOpening(
                    serverId = serverId,
                    sessionId = sessionId,
                    cwd = cwd,
                    // A cached paint is history, never live state: clear any stale streaming flag it
                    // carries. The live bridge republishes its true snapshot immediately on reattach
                    // (AcpChatSessionFacade.tryRestoreWarmSession), so a real in-flight turn re-arms the
                    // flag at once, while a turn that ended — or one whose owner died with the screen —
                    // can never strand the UI on STOP.
                    cachedSnapshot =
                        hub.snapshotFor(chatId)?.let { cached ->
                            cached.copy(
                                isStreaming = false,
                                messages =
                                    cached.messages.map { message ->
                                        if (message.isStreaming) message.copy(isStreaming = false) else message
                                    },
                            )
                        },
                ),
            hub = hub,
        )
    }
}
