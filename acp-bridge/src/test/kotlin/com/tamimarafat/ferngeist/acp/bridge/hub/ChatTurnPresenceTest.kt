package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTurnPresenceTest {
    @Test
    fun `viewing a finished turn clears it until another turn finishes off screen`() =
        runTest {
            val hub = newHub()
            val manager = hub.acquireChatManager()
            manager.forceConnectionState(AcpConnectionState.Connected)
            val bridge = SessionBridge("s1", null)
            bridge.runtime.beginHydration()
            bridge.runtime.completeHydration()
            val key =
                hub.register(
                    "srv",
                    "s1",
                    ChatRegistration(
                        gatewaySessionId = null,
                        gatewaySourceId = "",
                        agentId = "",
                        isConnected = { manager.isConnected },
                        isStreaming = { bridge.snapshot.value.isStreaming },
                        manager = manager,
                        snapshot = bridge.snapshot,
                    ),
                )
            bridge.sendPrompt("first", emptyList(), emptyList())
            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
            assertEquals(TurnStatus.FINISHED, hub.turnStatuses.value[key])

            hub.chatScreenOpened("srv", "s1", "")
            advanceUntilIdle()
            assertEquals(TurnStatus.UNKNOWN, hub.turnStatuses.value[key])
            bridge.sendPrompt("while open", emptyList(), emptyList())
            assertEquals(TurnStatus.WORKING, hub.turnStatuses.value[key])
            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
            assertEquals(TurnStatus.UNKNOWN, hub.turnStatuses.value[key])
            hub.chatScreenClosed("srv", "s1")
            advanceUntilIdle()
            assertEquals(TurnStatus.UNKNOWN, hub.turnStatuses.value[key])

            bridge.sendPrompt("off screen", emptyList(), emptyList())
            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
            assertEquals(TurnStatus.FINISHED, hub.turnStatuses.value[key])
            hub.chatScreenOpened("srv", "s1", "")
            advanceUntilIdle()
            bridge.sendPrompt("failure while open", emptyList(), emptyList())
            bridge.emitEvent(
                AppSessionEvent.ToolPermissionRequested(
                    toolCallId = "call",
                    requestId = "request",
                    title = "Approve?",
                    options = emptyList(),
                ),
            )
            assertEquals(TurnStatus.NEEDS_ATTENTION, hub.turnStatuses.value[key])
            bridge.emitEvent(AppSessionEvent.TurnComplete("error"))
            assertEquals(TurnStatus.ERROR, hub.turnStatuses.value[key])
            hub.close(key)
            bridge.close()
        }

    @Test
    fun `turn status follows the live bridge and is withdrawn while disconnected`() =
        runTest {
            val hub = newHub()
            val manager = hub.acquireChatManager()
            manager.forceConnectionState(AcpConnectionState.Connected)
            val bridge = SessionBridge("s1", null)
            bridge.runtime.beginHydration()
            bridge.runtime.completeHydration()
            val key =
                hub.register(
                    "srv",
                    "s1",
                    ChatRegistration(
                        gatewaySessionId = null,
                        gatewaySourceId = "",
                        agentId = "",
                        isConnected = { manager.isConnected },
                        isStreaming = { bridge.snapshot.value.isStreaming },
                        manager = manager,
                        snapshot = bridge.snapshot,
                    ),
                )
            advanceUntilIdle()
            bridge.sendPrompt("go", emptyList(), emptyList())
            assertEquals(mapOf(key to TurnStatus.WORKING), hub.turnStatuses.value)

            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
            assertEquals(mapOf(key to TurnStatus.FINISHED), hub.turnStatuses.value)

            // A recovered transport alone cannot republish the retained outcome.
            manager.forceConnectionState(AcpConnectionState.Disconnected)
            advanceUntilIdle()
            assertFalse(key in hub.turnStatuses.value)
            manager.forceConnectionState(AcpConnectionState.Connected)
            advanceUntilIdle()
            assertFalse(key in hub.turnStatuses.value)
            bridge.runtime.markReady()
            advanceUntilIdle()
            assertEquals(mapOf(key to TurnStatus.UNKNOWN), hub.turnStatuses.value)
            bridge.sendPrompt("again", emptyList(), emptyList())
            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
            assertEquals(mapOf(key to TurnStatus.FINISHED), hub.turnStatuses.value)

            hub.close(key)
            assertFalse(key in hub.turnStatuses.value)
            bridge.close()
        }

    @Test
    fun `evicting a chat withdraws its turn status`() =
        runTest {
            val hub = newHub(maxHot = 1, maxGateway = 1)
            val bridge = SessionBridge("s1", null)
            bridge.runtime.beginHydration()
            bridge.runtime.completeHydration()
            val key =
                hub.register(
                    "srv",
                    "s1",
                    ChatRegistration(
                        gatewaySessionId = null,
                        gatewaySourceId = "",
                        agentId = "",
                        isConnected = { true },
                        isStreaming = { bridge.snapshot.value.isStreaming },
                        snapshot = bridge.snapshot,
                    ),
                )
            bridge.sendPrompt("go", emptyList(), emptyList())
            assertEquals(TurnStatus.WORKING, hub.turnStatuses.value[key])

            hub.registerChat(sessionId = "second")

            assertFalse(hub.isTracked("srv", "s1"))
            assertFalse(key in hub.turnStatuses.value)
            bridge.close()
        }
}

/** Drives the connection state shared by hub lifecycle tests without a real ACP server. */
internal fun AcpConnection.forceConnectionState(state: AcpConnectionState) {
    val stateField = AcpConnection::class.java.getDeclaredField("_connectionState")
    stateField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val stateFlow = stateField.get(this) as MutableStateFlow<AcpConnectionState>
    stateFlow.value = state
}
