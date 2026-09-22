package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSwitcherTest : ChatViewModelTestBase() {
    @Test
    fun `switcher state lists all live sessions with current flagged`() =
        runTest {
            val hub =
                ChatConnectionHub(
                    gatewayRepository = null,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    connectivityObserver = FakeConnectivityObserver(),
                )
            val targets =
                FakeLaunchableTargetRepository(
                    listOf(
                        LaunchableTarget.Manual(ServerConfig(id = "srvA", name = "Alpha", host = "a")),
                        LaunchableTarget.Manual(ServerConfig(id = "srvB", name = "Beta", host = "b")),
                    ),
                )
            val sessions = FakeSessionRepository()
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
                    sessionRepository = sessions,
                    launchableTargetRepository = targets,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "srvA",
                                "sessionId" to "mine",
                                "cwd" to "/a",
                            ),
                        ),
                )
            advanceUntilIdle()

            val state = viewModel.switcherUiState.first { it.liveTotalCount == 3 }
            assertEquals(3, state.liveTotalCount)
            assertTrue(state.hasOthers)
            assertEquals(listOf("srvA", "srvB"), state.groups.map { it.serverId })
            val currentRows = state.groups.flatMap { it.sessions }.filter { it.isCurrent }
            assertEquals(listOf("mine"), currentRows.map { it.sessionId })
            viewModel.clearForTest()
        }

    private suspend fun connectSession(
        hub: ChatConnectionHub,
        serverId: String,
        sessionId: String,
    ) {
        val manager = hub.acquireChatManager()
        hub.register(
            serverId = serverId,
            sessionId = sessionId,
            gatewaySessionId = "gw-$sessionId",
            gatewaySourceId = "src-$serverId",
            agentId = "agent-1",
            isConnected = { true },
            isStreaming = { false },
            manager = manager,
        )
        manager.forceConnected()
    }

    /**
     * Drives a manager to Connected the way a successful transport connect
     * would (mirrors the hub suite's helper: flips the private StateFlow the
     * transport updates, so presence observers cannot tell the difference).
     */
    private fun AcpConnectionManager.forceConnected() {
        val managerClass = AcpConnectionManager::class.java
        val orchestraField = managerClass.getDeclaredField("orchestra")
        orchestraField.isAccessible = true
        val orchestra = orchestraField.get(this)
        val stateField = orchestra.javaClass.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(orchestra) as MutableStateFlow<AcpConnectionState>
        stateFlow.value = AcpConnectionState.Connected
    }
}
