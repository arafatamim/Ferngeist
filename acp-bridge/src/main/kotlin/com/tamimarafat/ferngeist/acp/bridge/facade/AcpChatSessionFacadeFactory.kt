package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManagerFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CoroutineScope

/**
 * ACP-backed implementation of [ChatSessionFacadeFactory].
 *
 * This keeps ACP wiring outside the feature layer while still allowing the
 * ViewModel to create a per-session facade using runtime parameters.
 */
class AcpChatSessionFacadeFactory(
    private val managerFactory: AcpConnectionManagerFactory,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val gatewayRepository: GatewayRepository,
) : ChatSessionFacadeFactory {
    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade =
        AcpChatSessionFacade(
            scope = scope,
            connectionManager = managerFactory.create(scope),
            launchableTargetRepository = launchableTargetRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            gatewayRepository = gatewayRepository,
            serverId = serverId,
            initialSessionId = sessionId,
            cwd = cwd,
        )
}
