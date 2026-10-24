package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.content.res.Resources
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayAgentSecurity
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddAgentRiskDisclosureTest {
    // Per-id sentinel text: a wrong resource id, or a format argument dropped on the floor,
    // cannot pass the assertions below.
    private val res =
        mockk<Resources> {
            every { getString(any()) } answers { "string-${firstArg<Int>()}" }
            every { getString(any(), *anyVararg()) } answers {
                val formatArgs = (args[1] as Array<*>).joinToString("|")
                "string-${firstArg<Int>()}($formatArgs)"
            }
        }

    @Test
    fun `risk lines name the agent and the gateway host`() {
        val agent = sampleAgent()

        val lines = addAgentRiskLines(res = res, agent = agent, gatewayHost = "76.13.225.224:5788")

        assertEquals(4, lines.size)
        assertTrue(
            "first line should name the agent: ${lines[0]}",
            lines[0].contains(agent.displayName) && lines[0].contains(agent.id),
        )
        assertTrue("second line should carry the host: ${lines[1]}", lines[1].contains("76.13.225.224:5788"))
    }

    @Test
    fun `risk lines fall back to the gateway placeholder when the host is blank`() {
        val lines = addAgentRiskLines(res = res, agent = sampleAgent(), gatewayHost = "   ")

        val fallback = res.getString(R.string.serverlist_risk_gateway_host_fallback)
        assertTrue("second line should carry the fallback text: ${lines[1]}", lines[1].contains(fallback))
    }

    private fun sampleAgent(): GatewayAgent =
        GatewayAgent(
            id = "mock-acp",
            displayName = "Mock ACP Agent",
            detected = true,
            manifestValid = true,
            security = GatewayAgentSecurity(allowsRemoteStart = true),
        )
}
