package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.content.res.Resources
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayAgentSecurity
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class AddAgentRiskDisclosureTest {
    private val mockResources = mockk<Resources>(relaxed = true)

    @Test
    fun `risk lines are produced without crashing`() {
        val lines =
            addAgentRiskLines(
                res = mockResources,
                agent = sampleAgent(),
                gatewayHost = "76.13.225.224:5788",
            )
        assertTrue(lines.isNotEmpty())
    }

    @Test
    fun `risk lines are produced when host is blank`() {
        val lines =
            addAgentRiskLines(
                res = mockResources,
                agent = sampleAgent(),
                gatewayHost = "   ",
            )
        assertTrue(lines.isNotEmpty())
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
