package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionSurface
import com.tamimarafat.ferngeist.acp.bridge.hub.GatewayEndpoint
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Facade attendance behavior against a recording [ChatConnectionSurface].
 *
 * Only the transport-free paths are drivable here: everything past
 * registerConnectingWithHub sits behind a connected [AcpConnectionManager],
 * which is a concrete final class that only reaches Connected through a real
 * gateway handshake — so registration timing, sentinel skipping, and snapshot
 * re-keying stay covered at the ChatViewModel level (fake facade) and hub
 * level (ChatConnectionHubTest), and the seam exists to make the facade's own
 * wiring testable once a manager seam lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcpChatSessionFacadeTest {
    private class RecordingSurface : ChatConnectionSurface {
        val abandoned = mutableListOf<AcpConnectionManager>()

        override fun acquireChatManager(): AcpConnectionManager = TODO("unused in facade attendance tests")

        override fun abandon(manager: AcpConnectionManager) {
            abandoned += manager
        }

        override fun managerFor(chatId: String): AcpConnectionManager? = null

        override fun snapshotFor(chatId: String): ChatSessionSnapshot? = null

        override fun storeSnapshot(
            chatId: String,
            snapshot: ChatSessionSnapshot,
        ) = Unit

        override suspend fun register(
            serverId: String,
            sessionId: String,
            gatewaySessionId: String?,
            gatewaySourceId: String,
            agentId: String,
            isConnected: () -> Boolean,
            isStreaming: () -> Boolean,
            manager: AcpConnectionManager?,
        ): String = TODO("unused in facade attendance tests")

        override fun hasLiveGatewaySession(
            gatewaySourceId: String,
            agentId: String,
        ): Boolean = false

        override suspend fun persistedGatewaySessionId(
            serverId: String,
            sessionId: String,
        ): String? = null

        override suspend fun ensureGatewayCapacity(endpoint: GatewayEndpoint) = Unit

        override fun refresh() = Unit
    }

    private class FakeLaunchableTargets : LaunchableTargetRepository {
        override fun getTargets(): Flow<List<LaunchableTarget>> = flowOf(emptyList())

        override suspend fun getTarget(id: String): LaunchableTarget? = null

        override suspend fun updatePreferredAuthMethod(
            targetId: String,
            methodId: String,
        ) = Unit

        override suspend fun deleteTarget(id: String) = Unit
    }

    private class FakeGatewaySources : GatewaySourceRepository {
        override fun getGateways(): Flow<List<GatewaySource>> = flowOf(emptyList())

        override suspend fun addGateway(gateway: GatewaySource) = Unit

        override suspend fun updateGateway(gateway: GatewaySource) = Unit

        override suspend fun deleteGateway(id: String) = Unit

        override suspend fun getGateway(id: String): GatewaySource? = null

        override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? = null
    }

    private class FakeGatewayRepo : GatewayRepository {
        override suspend fun fetchStatus(
            scheme: String,
            host: String,
        ) = TODO("unused in facade tests")

        override suspend fun startPairing(
            scheme: String,
            host: String,
        ) = TODO("unused in facade tests")

        override suspend fun getPairingStatus(
            scheme: String,
            host: String,
            challengeId: String,
        ) = TODO("unused in facade tests")

        override suspend fun completePairing(
            scheme: String,
            host: String,
            challengeId: String,
            code: String,
            deviceName: String,
        ) = TODO("unused in facade tests")

        override suspend fun refreshCredential(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO("unused in facade tests")

        override suspend fun fetchAgents(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO("unused in facade tests")

        override suspend fun startAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
            new: Boolean,
        ) = TODO("unused in facade tests")

        override suspend fun connectRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            sessionMode: String?,
        ) = TODO("unused in facade tests")

        override suspend fun restartRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            envVars: Map<String, String>,
        ) = TODO("unused in facade tests")

        override suspend fun fetchRuntimeLogs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ) = TODO("unused in facade tests")

        override suspend fun resumeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) = TODO("unused in facade tests")

        override suspend fun listGatewaySessions(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO("unused in facade tests")

        override suspend fun closeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) = Unit

        override suspend fun registerPushToken(
            scheme: String,
            host: String,
            gatewayCredential: String,
            token: String,
            platform: String,
        ) = Unit

        override suspend fun fetchWorkspaceFile(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String,
        ) = TODO("unused in facade tests")

        override suspend fun fetchGitStatus(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ) = TODO("unused in facade tests")

        override suspend fun fetchGitDiff(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String?,
        ) = TODO("unused in facade tests")
    }

    private fun newFacade(
        surface: RecordingSurface,
        scope: kotlinx.coroutines.CoroutineScope,
    ): AcpChatSessionFacade {
        val manager =
            AcpConnectionManager(
                connectivityObserver = ConnectivityObserverStub(initialState = true),
                gatewayRepository = null,
                scope = scope,
            )
        return AcpChatSessionFacade(
            scope = scope,
            connectionManager = manager,
            launchableTargetRepository = FakeLaunchableTargets(),
            gatewaySourceRepository = FakeGatewaySources(),
            gatewayRepository = FakeGatewayRepo(),
            serverId = "srv",
            initialSessionId = "s1",
            cwd = "/work",
            hub = surface,
        )
    }

    @Test
    fun `clear on an unregistered facade abandons its pending chat manager`() =
        runTest {
            val surface = RecordingSurface()
            val facade = newFacade(surface, scope = backgroundScope)

            facade.clear()

            // The screen closed before the transport ever registered (failed
            // spawn or never-connected load), so the hub-owned pending manager
            // must be released — otherwise it leaks in the hub's pending list
            // and keeps the process-wide aggregates alive.
            assertEquals(1, surface.abandoned.size)
        }
}
