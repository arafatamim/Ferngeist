package com.tamimarafat.ferngeist.feature.serverlist.ui

import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperAgent
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperAgentSecurity
import org.junit.Assert.assertTrue
import org.junit.Test

class AddAgentRiskDisclosureTest {

    @Test
    fun `risk lines include host agent id and execution warning`() {
        val lines = addAgentRiskLines(
            agent = sampleAgent(),
            companionHost = "76.13.225.224:5788",
        )

        assertTrue(lines.any { it.contains("76.13.225.224:5788") })
        assertTrue(lines.any { it.contains("mock-acp") })
        assertTrue(lines.any { it.contains("download", ignoreCase = true) })
        assertTrue(lines.any { it.contains("run", ignoreCase = true) || it.contains("code", ignoreCase = true) })
    }

    @Test
    fun `risk lines fall back when host is missing`() {
        val lines = addAgentRiskLines(
            agent = sampleAgent(),
            companionHost = "   ",
        )

        assertTrue(lines.any { it.contains("your desktop companion host", ignoreCase = true) })
    }

    private fun sampleAgent(): DesktopHelperAgent {
        return DesktopHelperAgent(
            id = "mock-acp",
            displayName = "Mock ACP Agent",
            detected = true,
            manifestValid = true,
            security = DesktopHelperAgentSecurity(allowsRemoteStart = true),
        )
    }
}
