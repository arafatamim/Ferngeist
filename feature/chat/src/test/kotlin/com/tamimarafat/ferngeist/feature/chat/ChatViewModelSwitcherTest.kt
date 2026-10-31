package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.facade.HubChatPresence
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatRegistration
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host the gateway-backed test target exposes, asserted on the close call. */
private const val GATEWAY_HOST = "gw.example"

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSwitcherTest : ChatViewModelTestBase() {
    @Test
    fun `switcher state lists all live sessions with current flagged`() =
        runTest {
            val hub = newTestHub()
            val targets =
                FakeLaunchableTargetRepository(
                    listOf(
                        LaunchableTarget.Manual(ServerConfig(id = "srvA", name = "Alpha", host = "a")),
                        LaunchableTarget.Manual(ServerConfig(id = "srvB", name = "Beta", host = "b")),
                    ),
                )
            val sessions = FakeChatCatalog()
            sessions.setSessions(
                "srvA",
                listOf(
                    SessionSummary(id = "mine", title = "Mine", cwd = "/a", updatedAt = 100L),
                    SessionSummary(id = "other", title = "Other", cwd = "/a", updatedAt = 50L),
                ),
            )
            sessions.setSessions(
                "srvB",
                listOf(SessionSummary(id = "far", title = "Far", cwd = "/b", updatedAt = 70L)),
            )
            connectSession(hub, "srvA", "mine")
            connectSession(hub, "srvA", "other")
            connectSession(hub, "srvB", "far")

            val viewModel =
                createViewModel(
                    chatConnectionHub = hub,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "srvA",
                                "sessionId" to "mine",
                                "cwd" to "/a",
                            ),
                        ),
                    repositories = testRepositories(sessionRepository = sessions, launchableTargetRepository = targets),
                )
            advanceUntilIdle()

            val state = viewModel.switcherUiState.first { it.liveTotalCount == 3 }
            assertEquals(3, state.liveTotalCount)
            assertTrue(state.hasOthers)
            assertEquals(listOf("a", "b"), state.groups.map { it.projectName })
            val currentRows = state.groups.flatMap { it.sessions }.filter { it.isCurrent }
            assertEquals(listOf("mine"), currentRows.map { it.sessionId })
            viewModel.clearForTest()
        }

    @Test
    fun `closing a switcher row releases the session through the hub`() =
        runTest {
            val gateway = FakeGatewayRepository()
            val hub = newHub(gateway)
            val sessions = FakeChatCatalog()
            sessions.setSessions(
                "srvA",
                listOf(
                    SessionSummary(id = "mine", title = "Mine", cwd = "/a", updatedAt = 100L),
                    SessionSummary(id = "other", title = "Other", cwd = "/a", updatedAt = 50L),
                ),
            )
            connectSession(hub, "srvA", "mine")
            connectSession(hub, "srvA", "other")
            val viewModel =
                createViewModel(
                    chatConnectionHub = hub,
                    savedStateHandle = sessionHandle(),
                    repositories =
                        testRepositories(
                            sessionRepository = sessions,
                            launchableTargetRepository = FakeLaunchableTargetRepository(listOf(gatewayTarget())),
                        ),
                )
            advanceUntilIdle()
            assertEquals(2, viewModel.switcherUiState.first { it.liveTotalCount == 2 }.liveTotalCount)

            viewModel.dispatch(ChatIntent.CloseSession(serverId = "srvA", sessionId = "other"))
            advanceUntilIdle()

            // The gateway DELETE carries the closed session's own runtime id and the
            // endpoint from the row's server, not this chat's.
            assertEquals(listOf(GATEWAY_HOST to "gw-other"), gateway.closedSessions)
            assertEquals(1, viewModel.switcherUiState.first { it.liveTotalCount == 1 }.liveTotalCount)
            viewModel.clearForTest()
        }

    @Test
    fun `closing a streaming session is refused and leaves it live`() =
        runTest {
            val gateway = FakeGatewayRepository()
            val hub = newHub(gateway)
            val sessions = FakeChatCatalog()
            sessions.setSessions(
                "srvA",
                listOf(
                    SessionSummary(id = "mine", title = "Mine", cwd = "/a", updatedAt = 100L),
                    SessionSummary(id = "busy", title = "Busy", cwd = "/a", updatedAt = 50L),
                ),
            )
            connectSession(hub, "srvA", "mine")
            connectSession(hub, "srvA", "busy", streaming = true)
            val viewModel =
                createViewModel(
                    chatConnectionHub = hub,
                    savedStateHandle = sessionHandle(),
                    repositories =
                        testRepositories(
                            sessionRepository = sessions,
                            launchableTargetRepository = FakeLaunchableTargetRepository(listOf(gatewayTarget())),
                        ),
                )
            advanceUntilIdle()

            val effects = mutableListOf<ChatEffect>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
            viewModel.dispatch(ChatIntent.CloseSession(serverId = "srvA", sessionId = "busy"))
            advanceUntilIdle()
            collector.cancel()

            val refusal =
                effects
                    .filterIsInstance<ChatEffect.ShowError>()
                    .map { it.message }
                    .firstOrNull { it.startsWith("This session is still responding") }
            assertNotNull(refusal)
            assertTrue(gateway.closedSessions.isEmpty())
            assertEquals(2, viewModel.switcherUiState.first { it.liveTotalCount == 2 }.liveTotalCount)
            viewModel.clearForTest()
        }

    @Test
    fun `switcher updates turn state without a connection or listing change`() =
        runTest {
            val hub = newTestHub()
            val targets = FakeLaunchableTargetRepository(listOf(gatewayTarget()))
            val sessions = FakeChatCatalog()
            sessions.setSessions("srvA", listOf(SessionSummary(id = "mine"), SessionSummary(id = "other")))
            connectSession(hub, "srvA", "mine")
            connectSession(hub, "srvA", "other")
            val statuses = MutableStateFlow(mapOf("srvA/other" to TurnActivity(TurnStatus.WORKING)))
            val controller =
                ChatSwitcherController(
                    scope = backgroundScope,
                    presence = HubChatPresence(hub, targets, FakeGatewayRepository(), turnStatuses = statuses),
                    chatListings = sessions,
                    launchableTargetRepository = targets,
                    serverId = "srvA",
                    currentSessionId = { "mine" },
                )
            val observed = mutableListOf<SwitcherUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.state.toList(observed) }
            runCurrent()
            val working = observed.last().groups.single()
            assertEquals(
                TurnStatus.WORKING,
                working.sessions
                    .last()
                    .turnStatus.status,
            )

            statuses.value =
                mapOf(
                    "srvA/other" to TurnActivity(TurnStatus.NEEDS_ATTENTION),
                    "different/other" to TurnActivity(TurnStatus.ERROR),
                )
            runCurrent()
            val attentionGroup = observed.last().groups.single()
            val attention = attentionGroup.sessions
            assertEquals(TurnStatus.NEEDS_ATTENTION, attention.last().turnStatus.status)
            assertEquals(TurnStatus.UNKNOWN, attention.first().turnStatus.status)
            assertTrue(attention.first().isCurrent)

            statuses.value = mapOf("srvA/other" to TurnActivity(TurnStatus.FINISHED))
            runCurrent()
            val finished = observed.last().groups.single()
            assertEquals(
                TurnStatus.FINISHED,
                finished.sessions
                    .last()
                    .turnStatus.status,
            )
        }

    private fun sessionHandle(): SavedStateHandle =
        SavedStateHandle(
            mapOf(
                "serverId" to "srvA",
                "sessionId" to "mine",
                "cwd" to "/a",
            ),
        )

    /** Gateway-backed target whose binding id is the server id the switcher groups by. */
    private fun gatewayTarget(serverId: String = "srvA"): LaunchableTarget.GatewayAgent =
        LaunchableTarget.GatewayAgent(
            binding =
                GatewayAgentBinding(
                    id = serverId,
                    name = "Alpha",
                    gatewaySourceId = "src-1",
                    agentId = "agent-1",
                ),
            gatewaySource =
                GatewaySource(
                    id = "src-1",
                    name = "Alpha",
                    host = GATEWAY_HOST,
                    gatewayCredential = "cred",
                ),
        )

    private fun newHub(gatewayRepository: FakeGatewayRepository): ChatConnectionHub = newTestHub(gatewayRepository)

    private fun connectSession(
        hub: ChatConnectionHub,
        serverId: String,
        sessionId: String,
        streaming: Boolean = false,
    ) {
        val manager = hub.acquireChatManager()
        hub.register(
            serverId = serverId,
            sessionId = sessionId,
            registration =
                ChatRegistration(
                    gatewaySessionId = "gw-$sessionId",
                    gatewaySourceId = "src-$serverId",
                    agentId = "agent-1",
                    isConnected = { true },
                    isStreaming = { streaming },
                    manager = manager,
                ),
        )
        manager.forceConnected()
    }

    /**
     * Drives a connection to Connected the way a successful transport connect
     * would (mirrors the hub suite's helper: flips the private StateFlow the
     * transport updates, so presence observers cannot tell the difference).
     */
    private fun AcpConnection.forceConnected() {
        val stateField = AcpConnection::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(this) as MutableStateFlow<AcpConnectionState>
        stateFlow.value = AcpConnectionState.Connected
    }
}
