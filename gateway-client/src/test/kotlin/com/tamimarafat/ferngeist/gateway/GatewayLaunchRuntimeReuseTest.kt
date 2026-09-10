package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that [launchGatewayRuntime] honours an explicit [launchGatewayRuntime] reuse
 * target: attaching to a known runtime must not start (or let the gateway pick) a
 * different agent process, because a gateway runtime leases exactly one session and
 * a picked runtime would hand back whichever session it already holds.
 */
class GatewayLaunchRuntimeReuseTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val bodies = mutableListOf<String>()

    @Test
    fun `reuseRuntimeId attaches to that runtime without starting another agent`() =
        runTest {
            val requested = mutableListOf<String>()
            val repo = repo(requested = requested)

            val result =
                launchGatewayRuntime(
                    gatewayRepository = repo,
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                    reuseRuntimeId = REUSED_RUNTIME,
                )

            val launched = result.getOrThrow()
            assertEquals(REUSED_RUNTIME, launched.runtime.id)
            assertFalse(
                "a reuse launch must not start a new agent runtime: $requested",
                requested.any { it.contains("/start") },
            )
            assertTrue(
                "the connect handoff must target the reused runtime: $requested",
                requested.any { it.contains("/runtimes/$REUSED_RUNTIME/connect") },
            )
        }

    @Test
    fun `without a reuse target the gateway picks the runtime via startAgent`() =
        runTest {
            val requested = mutableListOf<String>()
            val repo = repo(requested = requested)

            val result =
                launchGatewayRuntime(
                    gatewayRepository = repo,
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                )

            assertEquals(STARTED_RUNTIME, result.getOrThrow().runtime.id)
            assertTrue(
                "startAgent is the runtime-selection path when no target is given",
                requested.any { it.contains("/start") },
            )
        }

    @Test
    fun `a fresh launch asks the start endpoint for its own runtime`() =
        runTest {
            val requested = mutableListOf<String>()

            val result =
                launchGatewayRuntime(
                    gatewayRepository = repo(requested),
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                    fresh = true,
                )

            result.getOrThrow()
            val startBodies = bodies.filter { it.isNotBlank() }
            assertTrue(
                "without {\"new\":true} on the start request the gateway reuses the runtime another chat holds: $startBodies",
                startBodies.any { it.contains("\"new\":true") },
            )
        }

    private fun repo(requested: MutableList<String>): GatewayRepositoryImpl {
        val engine =
            MockEngine { request ->
                requested += request.url.encodedPath
                val path = request.url.encodedPath
                when {
                    path.endsWith("/start") -> {
                        bodies += (request.body as? TextContent)?.text.orEmpty()
                        respond(
                            """{"runtime":{"id":"$STARTED_RUNTIME","status":"running","agentId":"pi-acp"}}""",
                            HttpStatusCode.OK,
                        )
                    }

                    path.endsWith("/connect") -> respond(CONNECT_BODY, HttpStatusCode.OK)

                    else -> respond("{}", HttpStatusCode.OK)
                }
            }
        return GatewayRepositoryImpl(HttpClient(engine), json)
    }

    private class FakeGatewaySources : GatewaySourceRepository {
        override fun getGateways(): Flow<List<GatewaySource>> = flowOf(emptyList())

        override suspend fun addGateway(gateway: GatewaySource) = Unit

        override suspend fun updateGateway(gateway: GatewaySource) = Unit

        override suspend fun deleteGateway(id: String) = Unit

        override suspend fun getGateway(id: String): GatewaySource? = null

        override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? = null
    }

    private companion object {
        const val REUSED_RUNTIME = "runtime-recorded"
        const val STARTED_RUNTIME = "runtime-picked"

        val SOURCE =
            GatewaySource(
                id = "source-1",
                name = "gw",
                scheme = "http",
                host = "gw.local:5788",
                gatewayCredential = "cred",
            )

        val CONNECT_BODY =
            """
            {"runtimeId":"$REUSED_RUNTIME","scheme":"http","host":"gw.local:5788",
             "websocketUrl":"ws://gw.local:5788/v1/acp","websocketPath":"/v1/acp",
             "bearerToken":"tok","sessionId":"gw-session","attachToken":"attach"}
            """.trimIndent()
    }
}
