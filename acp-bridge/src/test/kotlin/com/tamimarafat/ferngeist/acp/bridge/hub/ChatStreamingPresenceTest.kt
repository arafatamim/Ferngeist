package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.facade.mapSnapshot
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamingPresenceTest {
    @Test
    fun `turn completion clears presence without a screen publishing another snapshot`() =
        runTest {
            val hub = newHub()
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
            hub.storeSnapshot(key, mapSnapshot(bridge.snapshot.value))
            assertTrue(key in hub.streamingChatIds.value)

            // The facade's screen observer is gone; only the live session hears completion.
            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))

            assertFalse(key in hub.streamingChatIds.value)
            bridge.close()
        }

    @Test
    fun `closed chat cannot regain streaming presence from its old bridge`() =
        runTest {
            val hub = newHub()
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
            hub.close(key)
            bridge.sendPrompt("late", emptyList(), emptyList())
            assertTrue(bridge.snapshot.value.isStreaming)
            assertFalse(key in hub.streamingChatIds.value)
            bridge.close()
        }
}
