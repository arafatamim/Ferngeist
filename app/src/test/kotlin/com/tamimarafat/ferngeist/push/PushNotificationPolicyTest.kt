package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.core.model.ChatPresence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationPolicyTest {
    private fun chat(
        serverId: String = "srv-1",
        sessionId: String = "sess-1",
        gatewaySourceId: String? = "gw-1",
    ) = ChatPresence(
        serverId = serverId,
        sessionId = sessionId,
        cwd = "/",
        gatewaySourceId = gatewaySourceId,
    )

    @Test
    fun `suppresses when foregrounded on the exact session and gateway`() {
        assertTrue(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                foregroundChat = chat(sessionId = "sess-1"),
                targetGatewayId = "gw-1",
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `suppresses across re-pair when local ids differ but the gateway matches`() {
        // The presence entry's local server id and the push's translated id differ (a
        // duplicate record from re-pairing), but both resolve to the same gatewaySourceId.
        assertTrue(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                foregroundChat = chat(serverId = "local-old", sessionId = "sess-1"),
                targetGatewayId = "gw-1",
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `suppresses on session match when the gateway id could not be resolved`() {
        // gatewaySourceId is unknown (null) but the session is unique and matches.
        assertTrue(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                foregroundChat = chat(sessionId = "sess-1", gatewaySourceId = null),
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
                foregroundChat = chat(sessionId = "sess-1"),
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
                foregroundChat = chat(sessionId = "sess-1"),
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
                foregroundChat = chat(sessionId = "sess-1", gatewaySourceId = "gw-1"),
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
                foregroundChat = null,
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
                foregroundChat = chat(),
                targetGatewayId = "gw-1",
                targetSessionId = null,
            ),
        )
    }

    @Test
    fun `does not suppress a pooled chat after back-out when onScreenChat is null`() {
        // After the user backs out of a chat its transport may stay pooled (so the hub's
        // tapTarget still names it), but onScreenChat — the suppression input — is null.
        assertFalse(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                foregroundChat = null,
                targetGatewayId = "gw-1",
                targetSessionId = "sess-1",
            ),
        )
    }

    @Test
    fun `suppresses on session match when the push carries no gateway id`() {
        // The push omits the gateway id (legacy payload) while the foreground chat's
        // gatewaySourceId is known: a null target cannot be shown to come from another
        // gateway, so it falls back to the conclusive session-id match and the push is
        // still suppressed (matches the pre-refactor truth table).
        assertTrue(
            PushNotificationPolicy.shouldSuppress(
                isAppForeground = true,
                foregroundChat = chat(sessionId = "sess-1"),
                targetGatewayId = null,
                targetSessionId = "sess-1",
            ),
        )
    }
}
