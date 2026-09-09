package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CoroutineScope

/**
 * ACP-backed implementation of [ChatSessionFacadeFactory].
 *
 * Keeps ACP wiring outside the feature layer while still allowing the
 * ViewModel to create a per-session facade using runtime parameters. Chat
 * managers are acquired from the shared [ChatConnectionHub], which owns their
 * lifetime: a returning screen reuses the live transport instead of
 * reconnecting, so backgrounded chats keep streaming (hot cap 3, never
 * evicting a streaming chat). The hub tears managers down on eviction or
 * explicit close.
 */
class AcpChatSessionFacadeFactory(
    private val hub: ChatConnectionHub,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val gatewayRepository: GatewayRepository,
) : ChatSessionFacadeFactory {
    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade {
        val chatId = "$serverId/$sessionId"
        val hubSnapshot = hub.snapshotFor(chatId)
        val existing = hub.managerFor(chatId)
        if (existing != null) {
            return AcpChatSessionFacade(
                scope = scope,
                connectionManager = existing,
                launchableTargetRepository = launchableTargetRepository,
                gatewaySourceRepository = gatewaySourceRepository,
                gatewayRepository = gatewayRepository,
                serverId = serverId,
                initialSessionId = sessionId,
                cwd = cwd,
                hub = hub,
                initialCachedSnapshot = hubSnapshot,
            )
        }
        val manager = hub.acquireChatManager()
        return AcpChatSessionFacade(
            scope = scope,
            connectionManager = manager,
            launchableTargetRepository = launchableTargetRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            gatewayRepository = gatewayRepository,
            serverId = serverId,
            initialSessionId = sessionId,
            cwd = cwd,
            hub = hub,
            initialCachedSnapshot = hubSnapshot,
        )
    }
}
