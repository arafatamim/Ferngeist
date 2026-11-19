package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.content.res.Resources
import com.tamimarafat.ferngeist.feature.serverlist.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent prompt's only body text. A drop of the host argument would still compile and
 * render "%1$s" to the user, so the disclosure is asserted through the real formatter.
 */
class AgentRiskDisclosureTest {
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
    fun `risk line names the host that runs the agent`() {
        val line = agentRiskLine(res = res, gatewayHost = "76.13.225.224:5788")

        assertTrue("the disclosure must name the host: $line", line.contains("76.13.225.224:5788"))
    }

    @Test
    fun `risk line falls back to the gateway placeholder when the host is blank`() {
        val fallback = res.getString(R.string.serverlist_risk_gateway_host_fallback)

        val line = agentRiskLine(res = res, gatewayHost = "   ")

        assertTrue("the disclosure must carry the fallback text: $line", line.contains(fallback))
    }
}
