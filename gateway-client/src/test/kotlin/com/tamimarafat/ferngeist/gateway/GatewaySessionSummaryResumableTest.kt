package com.tamimarafat.ferngeist.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [GatewaySessionSummary.isResumable] to the gateway's own reconnect
 * vocabulary.
 *
 * This decides whether a chat reattaches to the runtime its session lives on or
 * mints a new one. Treating a dead session as resumable points the chat at a
 * runtime the gateway has already killed, which surfaces as a chat that never
 * loads — so `failed` must stay out.
 */
class GatewaySessionSummaryResumableTest {
    @Test
    fun `active and disconnected sessions keep a live lease`() {
        assertTrue(summary("active").isResumable)
        assertTrue(summary("disconnected").isResumable)
    }

    @Test
    fun `dead sessions are not resumable`() {
        assertFalse(summary("failed").isResumable)
        assertFalse(summary("closing").isResumable)
    }

    @Test
    fun `an unknown status is not assumed resumable`() {
        assertFalse(summary("something-new").isResumable)
    }

    private fun summary(status: String) =
        GatewaySessionSummary(
            sessionId = "gw-session",
            runtimeId = "runtime-1",
            agentId = "pi-acp",
            status = status,
        )
}
