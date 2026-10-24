package com.tamimarafat.ferngeist.acp.bridge.connection

import com.tamimarafat.ferngeist.acp.bridge.hub.FakeGatewayRepo
import com.tamimarafat.ferngeist.acp.bridge.hub.FakeGatewaySources
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.gateway.GatewayProtocol
import com.tamimarafat.ferngeist.gateway.launch.GatewayRuntimeLauncher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server list connects through [buildGatewayLaunchContext], so a gateway
 * on an unsupported protocol must fail here with the mismatch message —
 * not later as an opaque 404/422 from the connect call.
 */
class GatewayLaunchSupportTest {
    @Test
    fun `a v1 gateway fails the server connect without calling connect`() =
        runTest {
            val repo = FakeGatewayRepo().apply { protocolVersion = "v1" }
            val result = buildGatewayLaunchContext(GatewayRuntimeLauncher(repo, FakeGatewaySources()), target())

            assertTrue("a v1 gateway must fail the server connect", result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(
                "the mismatch message must name the protocol version: $message",
                message.contains("protocol version"),
            )
            assertEquals("a rejected gateway must never reach connect", 0, repo.connectCalls)
        }

    @Test
    fun `a v2 gateway still launches the server connect`() =
        runTest {
            val repo = FakeGatewayRepo().apply { protocolVersion = GatewayProtocol.CURRENT }
            val result = buildGatewayLaunchContext(GatewayRuntimeLauncher(repo, FakeGatewaySources()), target())

            assertTrue("a v2 gateway must launch: ${result.exceptionOrNull()?.message}", result.isSuccess)
            assertEquals(1, repo.connectCalls)
        }

    private fun target(): LaunchableTarget.GatewayAgent {
        val source =
            GatewaySource(
                id = "source-1",
                name = "gw",
                scheme = "http",
                host = "gw.local:5788",
                gatewayCredential = "cred",
            )
        return LaunchableTarget.GatewayAgent(
            binding =
                GatewayAgentBinding(
                    name = "agent",
                    gatewaySourceId = source.id,
                    agentId = "pi-acp",
                ),
            gatewaySource = source,
        )
    }
}
