package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.hub.IdleListingSession
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayLaunchPlanTest {
    private fun session(
        id: String,
        runtime: String,
        status: String = "disconnected",
    ) = GatewaySessionSummary(sessionId = id, runtimeId = runtime, agentId = "agent", status = status)

    private val reuseAny = GatewayLaunchPlan(new = false, reuseRuntimeId = null)
    private val isolated = GatewayLaunchPlan(new = true, reuseRuntimeId = null)

    @Test
    fun idleListingSession_onlyOtherHolder_reusesItsRuntime() {
        val plan =
            planGatewayLaunch(
                sessions = listOf(session("listing", "rt-listing")),
                ownSessionId = null,
                idleListing = IdleListingSession("listing", "rt-listing"),
                liveInHub = false,
            )

        assertEquals(GatewayLaunchPlan(new = false, reuseRuntimeId = "rt-listing"), plan)
    }

    @Test
    fun idleListingSession_besideAnotherChatsSession_spawnsIsolated() {
        val plan =
            planGatewayLaunch(
                sessions = listOf(session("listing", "rt-listing"), session("other-chat", "rt-other")),
                ownSessionId = null,
                idleListing = IdleListingSession("listing", "rt-listing"),
                liveInHub = false,
            )

        assertEquals(isolated, plan)
    }

    @Test
    fun idleListingSession_noLongerResumable_isNotReused() {
        val plan =
            planGatewayLaunch(
                sessions = listOf(session("listing", "rt-listing", status = "failed")),
                ownSessionId = null,
                idleListing = IdleListingSession("listing", "rt-listing"),
                liveInHub = false,
            )

        assertEquals(reuseAny, plan)
    }

    @Test
    fun withoutIdleListing_otherResumableSession_spawnsIsolated() {
        val plan =
            planGatewayLaunch(
                sessions = listOf(session("listing", "rt-listing")),
                ownSessionId = null,
                idleListing = null,
                liveInHub = false,
            )

        assertEquals(isolated, plan)
    }

    @Test
    fun ownLiveSession_winsOverIdleListing() {
        val plan =
            planGatewayLaunch(
                sessions = listOf(session("mine", "rt-mine"), session("listing", "rt-listing")),
                ownSessionId = "mine",
                idleListing = IdleListingSession("listing", "rt-listing"),
                liveInHub = false,
            )

        assertEquals(GatewayLaunchPlan(new = false, reuseRuntimeId = "rt-mine"), plan)
    }

    @Test
    fun liveChatInHub_spawnsIsolatedEvenWithIdleListing() {
        val plan =
            planGatewayLaunch(
                sessions = listOf(session("listing", "rt-listing")),
                ownSessionId = null,
                idleListing = IdleListingSession("listing", "rt-listing"),
                liveInHub = true,
            )

        assertEquals(isolated, plan)
    }

    @Test
    fun failedLookup_spawnsIsolated() {
        val plan =
            planGatewayLaunch(
                sessions = null,
                ownSessionId = null,
                idleListing = IdleListingSession("listing", "rt-listing"),
                liveInHub = false,
            )

        assertEquals(isolated, plan)
    }
}
