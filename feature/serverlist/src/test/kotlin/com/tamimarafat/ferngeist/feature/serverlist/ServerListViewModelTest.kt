package com.tamimarafat.ferngeist.feature.serverlist

import app.cash.turbine.test
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLaunchEvent
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLaunchOutcome
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLaunchSession
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLauncher
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.core.model.store.RecentCwdStore
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.serverlist.consent.AgentLaunchConsentStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    private val agentLauncher = mockk<AgentLauncher>()
    private val launchSession = mockk<AgentLaunchSession>(relaxed = true)
    private val authEnvValueStore = mockk<AuthEnvValueStore>(relaxed = true)
    private val agentLaunchConsentStore = mockk<AgentLaunchConsentStore>(relaxed = true)
    private val sessionSettingsRepository = mockk<LaunchableTargetSessionSettingsRepository>(relaxed = true)
    private val recentCwdStore = mockk<RecentCwdStore>(relaxed = true)
    private val recentSelectionStore = mockk<RecentSelectionStore>(relaxed = true)
    private val connectionStateFlow = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Disconnected)
    private val eventsFlow = MutableSharedFlow<AgentLaunchEvent>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // The viewmodel clears `isLoading` on the first real emission of each query,
        // so both repository flows must actually emit. A relaxed mock returns a flow
        // that never does, and the loading flag would never clear.
        every { launchableTargetRepository.getTargets() } returns flowOf(emptyList())
        every { sessionRepository.getRecentSessions(any()) } returns flowOf(emptyList())
        every { agentLauncher.session(any()) } returns launchSession
        every { agentLauncher.liveServerIds } returns MutableStateFlow(emptySet())
        every { agentLauncher.hasWarmChat(any()) } returns false
        coEvery { agentLauncher.prewarmSessions(any()) } returns Unit
        every { launchSession.connectionState } returns connectionStateFlow
        every { launchSession.events } returns eventsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connectAndOpenServer should reuse existing connection if same server is already connected`() =
        runTest(testDispatcher) {
            val serverId = UUID.randomUUID().toString()
            val server =
                LaunchableTarget.Manual(
                    server = ServerConfig(id = serverId, name = "Test Server", host = "localhost"),
                )

            // Mock launch success to allow first connection to complete
            coEvery { launchSession.launch(any()) } returns
                AgentLaunchOutcome.Ready(
                    agentName = "Test Agent",
                    capabilities = emptyList(),
                )

            val viewModel = createViewModel()

            viewModel.events.test {
                // 1. Establish initial connection
                viewModel.connectAndOpenServer(server)
                advanceUntilIdle()

                // Verify it connected once
                coVerify(exactly = 1) { launchSession.launch(any()) }

                // Should have navigated
                val firstEvent = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions(serverId=$serverId) after first connect, got $firstEvent",
                    firstEvent is ServerListEvent.NavigateToSessions &&
                        firstEvent.serverId == serverId,
                )

                // 2. Setup "already connected" state for second call
                connectionStateFlow.value = ChatConnectionState.Connected
                every { launchSession.isConnected } returns true

                // 3. Second call with same server
                viewModel.connectAndOpenServer(server)
                advanceUntilIdle()

                // Verify that connect was NOT called again during the second attempt.
                coVerify(exactly = 1) { launchSession.launch(any()) }

                // Should have navigated AGAIN (instantly)
                val secondEvent = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions(serverId=$serverId) on reuse, got $secondEvent",
                    secondEvent is ServerListEvent.NavigateToSessions &&
                        secondEvent.serverId == serverId,
                )

                // 4. Same server, but the transport reports disconnected: the guard must not fire.
                every { launchSession.isConnected } returns false

                viewModel.connectAndOpenServer(server)
                val thirdEvent = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions(serverId=$serverId) after a real reconnect, got $thirdEvent",
                    thirdEvent is ServerListEvent.NavigateToSessions &&
                        thirdEvent.serverId == serverId,
                )
                coVerify(exactly = 2) { launchSession.launch(any()) }

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `connectAndOpenServer should skip reuse guard when different server is already connected`() =
        runTest(testDispatcher) {
            val serverAId = UUID.randomUUID().toString()
            val serverBId = UUID.randomUUID().toString()
            val serverA =
                LaunchableTarget.Manual(
                    server = ServerConfig(id = serverAId, name = "Server A", host = "localhost"),
                )
            val serverB =
                LaunchableTarget.Manual(
                    server = ServerConfig(id = serverBId, name = "Server B", host = "localhost"),
                )

            coEvery { launchSession.launch(any()) } returns
                AgentLaunchOutcome.Ready(
                    agentName = "Test Agent",
                    capabilities = emptyList(),
                )

            val viewModel = createViewModel()

            viewModel.events.test {
                // Connect to server A
                viewModel.connectAndOpenServer(serverA)
                val firstEvent = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions for server A, got $firstEvent",
                    firstEvent is ServerListEvent.NavigateToSessions &&
                        firstEvent.serverId == serverAId,
                )
                connectionStateFlow.value = ChatConnectionState.Connected
                every { launchSession.isConnected } returns true

                // Connect to server B — reuse guard should NOT fire (different serverId)
                viewModel.connectAndOpenServer(serverB)

                // Wait for the full connection flow to complete (via the event)
                val secondEvent = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions for server B, got $secondEvent",
                    secondEvent is ServerListEvent.NavigateToSessions &&
                        secondEvent.serverId == serverBId,
                )

                // Two servers, two launches: B did not ride on A's connection.
                coVerify(exactly = 1) { launchSession.launch(serverA) }
                coVerify(exactly = 1) { launchSession.launch(serverB) }

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `connectAndOpenServer should prewarm sessions before navigating`() =
        runTest(testDispatcher) {
            val serverId = UUID.randomUUID().toString()
            val server =
                LaunchableTarget.Manual(
                    server = ServerConfig(id = serverId, name = "Test Server", host = "localhost"),
                )

            coEvery { launchSession.launch(any()) } returns
                AgentLaunchOutcome.Ready(
                    agentName = "Test Agent",
                    capabilities = emptyList(),
                )

            val viewModel = createViewModel()

            viewModel.events.test {
                viewModel.connectAndOpenServer(server)
                val event = awaitItem()
                assertTrue(
                    "Expected NavigateToSessions, got $event",
                    event is ServerListEvent.NavigateToSessions &&
                        event.serverId == serverId,
                )

                // The listing warms the cache for the server we are about to open.
                coVerify(exactly = 1) { agentLauncher.prewarmSessions(serverId) }

                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel() =
        ServerListViewModel(
            ServerRepositories(
                launchableTargetRepository,
                gatewaySourceRepository,
                sessionRepository,
                sessionSettingsRepository,
            ),
            ServerLocalStores(authEnvValueStore, agentLaunchConsentStore, recentCwdStore, recentSelectionStore),
            agentLauncher,
            testDispatcher,
        )
}
