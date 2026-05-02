package com.tamimarafat.ferngeist.feature.serverlist

import app.cash.turbine.test
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AgentInfo
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import com.tamimarafat.ferngeist.feature.serverlist.consent.AgentLaunchConsentStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ServerListViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    
    private val gatewaySourceRepository = mockk<GatewaySourceRepository>(relaxed = true)
    private val launchableTargetRepository = mockk<LaunchableTargetRepository>(relaxed = true)
    private val sessionRepository = mockk<SessionRepository>(relaxed = true)
    private val connectionManager = mockk<AcpConnectionManager>(relaxed = true)
    private val gatewayRepository = mockk<GatewayRepository>(relaxed = true)
    private val authEnvValueStore = mockk<AuthEnvValueStore>(relaxed = true)
    private val agentLaunchConsentStore = mockk<AgentLaunchConsentStore>(relaxed = true)
    private val sessionSettingsRepository = mockk<LaunchableTargetSessionSettingsRepository>(relaxed = true)

    private val connectionStateFlow = MutableStateFlow<AcpConnectionState>(AcpConnectionState.Disconnected)
    private val eventsFlow = MutableSharedFlow<com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Mock Dispatchers.IO to use our test dispatcher
        // (Note: Requires mockk-static for Dispatchers if not using a library like CoroutineTestRule that handles it,
        // but simple way here is to just be aware of it)
        
        every { connectionManager.connectionState } returns connectionStateFlow
        every { connectionManager.events } returns eventsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connectAndOpenServer should reuse existing connection if same server is already connected`() = runTest(testDispatcher) {
        val serverId = UUID.randomUUID().toString()
        val server = LaunchableTarget.Manual(
            server = ServerConfig(id = serverId, name = "Test Server", host = "localhost")
        )
        
        // Mock initialize success to allow first connection to complete
        coEvery { connectionManager.connect(any()) } returns true
        coEvery { connectionManager.initialize() } returns AcpInitializeResult.Ready(
            agentInfo = AgentInfo("Test Agent", "1.0"),
            agentCapabilities = AcpAgentCapabilities(),
            authMethods = emptyList()
        )

        val viewModel = createViewModel()
        
        viewModel.events.test {
            // 1. Establish initial connection
            viewModel.connectAndOpenServer(server)
            advanceUntilIdle()

            // Verify it connected once
            coVerify(exactly = 1) { connectionManager.connect(any()) }
            
            // Should have navigated
            val firstEvent = awaitItem()
            assertTrue(
                "Expected NavigateToSessions(serverId=$serverId) after first connect, got $firstEvent",
                firstEvent is com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent.NavigateToSessions &&
                    firstEvent.serverId == serverId,
            )

            // 2. Setup "already connected" state for second call
            connectionStateFlow.value = AcpConnectionState.Connected
            every { connectionManager.isConnected } returns true

            // 3. Second call with same server
            viewModel.connectAndOpenServer(server)
            advanceUntilIdle()

            // Verify that connect was NOT called again during the second attempt.
            coVerify(exactly = 1) { connectionManager.connect(any()) }

            // Should have navigated AGAIN (instantly)
            val secondEvent = awaitItem()
            assertTrue(
                "Expected NavigateToSessions(serverId=$serverId) on reuse, got $secondEvent",
                secondEvent is com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent.NavigateToSessions &&
                    secondEvent.serverId == serverId,
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connectAndOpenServer should skip reuse guard when different server is already connected`() =
        runTest(testDispatcher) {
            val serverAId = UUID.randomUUID().toString()
            val serverBId = UUID.randomUUID().toString()
            val serverA = LaunchableTarget.Manual(
                server = ServerConfig(id = serverAId, name = "Server A", host = "localhost")
            )
            val serverB = LaunchableTarget.Manual(
                server = ServerConfig(id = serverBId, name = "Server B", host = "localhost")
            )

            coEvery { connectionManager.connect(any()) } returns true
            coEvery { connectionManager.initialize() } returns AcpInitializeResult.Ready(
                agentInfo = AgentInfo("Test Agent", "1.0"),
                agentCapabilities = AcpAgentCapabilities(),
                authMethods = emptyList()
            )

            val viewModel = createViewModel()

            viewModel.events.test {
                // Connect to server A
                viewModel.connectAndOpenServer(serverA)
                val firstEvent = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions for server A, got $firstEvent",
                    firstEvent is com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent.NavigateToSessions &&
                        firstEvent.serverId == serverAId,
                )
                connectionStateFlow.value = AcpConnectionState.Connected
                every { connectionManager.isConnected } returns true

                // Connect to server B — reuse guard should NOT fire (different serverId)
                viewModel.connectAndOpenServer(serverB)

                // Wait for the full connection flow to complete (via the event)
                val secondEvent = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions for server B, got $secondEvent",
                    secondEvent is com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent.NavigateToSessions &&
                        secondEvent.serverId == serverBId,
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `connectAndOpenServer should skip reuse guard when not connected`() =
        runTest(testDispatcher) {
            val serverId = UUID.randomUUID().toString()
            val server = LaunchableTarget.Manual(
                server = ServerConfig(id = serverId, name = "Test Server", host = "localhost")
            )

            coEvery { connectionManager.connect(any()) } returns true
            coEvery { connectionManager.initialize() } returns AcpInitializeResult.Ready(
                agentInfo = AgentInfo("Test Agent", "1.0"),
                agentCapabilities = AcpAgentCapabilities(),
                authMethods = emptyList()
            )

            val viewModel = createViewModel()

            viewModel.events.test {
                every { connectionManager.isConnected } returns false

                // connectAndOpenServer with isConnected=false should proceed past guard
                viewModel.connectAndOpenServer(server)
                val event = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions, got $event",
                    event is com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent.NavigateToSessions &&
                        event.serverId == serverId,
                )

                // Verify full connection lifecycle ran (guard was skipped)
                coVerify(exactly = 1) { connectionManager.connect(any()) }

                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel() = ServerListViewModel(
        gatewaySourceRepository,
        launchableTargetRepository,
        sessionRepository,
        connectionManager,
        gatewayRepository,
        authEnvValueStore,
        agentLaunchConsentStore,
        sessionSettingsRepository
    )
}
