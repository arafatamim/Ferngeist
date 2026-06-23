package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.core.model.store.ActiveChat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationPolicyTest {
    private fun chat(
        serverId: String = "srv-1",
        sessionId: String = "sess-1",
    ) = ActiveChat(serverId = serverId, sessionId = sessionId, cwd = "/", title = "t")

    @Test
    fun `suppresses when foregrounded on the exact session and gateway`() {
        assertTrue(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                activeChat = chat(sessionId = "sess-1"),
                activeChatGatewayId = "gw-1",
                targetGatewayId = "gw-1",
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `suppresses across re-pair when local ids differ but the gateway matches`() {
        // The active chat's local server id and the push's translated id differ (a
        // duplicate record from re-pairing), but both resolve to the same gatewayId.
        assertTrue(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                activeChat = chat(serverId = "local-old", sessionId = "sess-1"),
                activeChatGatewayId = "gw-1",
                targetGatewayId = "gw-1",
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `suppresses on session match when the gateway id could not be resolved`() {
        // gatewayId resolution failed (null) but the session is unique and matches.
        assertTrue(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                activeChat = chat(sessionId = "sess-1"),
                activeChatGatewayId = null,
                targetGatewayId = null,
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `never suppresses while backgrounded`() {
        assertFalse(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = false,
                activeChat = chat(sessionId = "sess-1"),
                activeChatGatewayId = "gw-1",
                targetGatewayId = "gw-1",
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `does not suppress a different session`() {
        assertFalse(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                activeChat = chat(sessionId = "sess-1"),
                activeChatGatewayId = "gw-1",
                targetGatewayId = "gw-1",
                targetSessionId = "sess-2",
            ),
        )
    }

    @Test
    fun `does not suppress when the same session belongs to a different gateway`() {
        assertFalse(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                activeChat = chat(sessionId = "sess-1"),
                activeChatGatewayId = "gw-1",
                targetGatewayId = "gw-2",
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `does not suppress when no chat is open`() {
        assertFalse(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                activeChat = null,
                activeChatGatewayId = "gw-1",
                targetGatewayId = "gw-1",
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `does not suppress when the push names no session`() {
        assertFalse(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                activeChat = chat(),
                activeChatGatewayId = "gw-1",
                targetGatewayId = "gw-1",
                targetSessionId = null,
            ),
        )
    }
}
