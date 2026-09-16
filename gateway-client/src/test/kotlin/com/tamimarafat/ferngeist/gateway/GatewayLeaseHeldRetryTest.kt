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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that a held runtime lease on connect retries once as an isolated
 * spawn instead of failing the chat.
 *
 * Two devices racing to the same runtime is the normal multi-device shape:
 * the gateway refuses the loser with 409 `runtime_lease_held`, and the app
 * must mint its own runtime via the existing `new` flag rather than surface
 * a dead chat. One retry only — a refused isolated spawn, a resumed own
 * session, or any other failure keeps its original outcome.
 */
class GatewayLeaseHeldRetryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `lease-held connect retries once as an isolated spawn`() =
        runTest {
            val startBodies = mutableListOf<String?>()
            var connects = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/start") -> {
                            startBodies += (request.body as? TextContent)?.text
                            respond(
                                """{"runtime":{"id":"runtime-a","status":"running","agentId":"pi-acp"}}""",
                                HttpStatusCode.OK,
                            )
                        }
                        else -> {
                            connects++
                            if (connects == 1) {
                                respond(
                                    """{"error":"runtime_lease_held"}""",
                                    HttpStatusCode.Conflict,
                                )
                            } else {
                                respond(
                                    """{"runtimeId":"runtime-b","scheme":"http","host":"gw.local:5788","websocketUrl":"ws://gw.local:5788/acp","websocketPath":"/acp","bearerToken":"b","sessionId":"sess-new","attachToken":"tok"}""",
                                    HttpStatusCode.OK,
                                )
                            }
                        }
                    }
                }
            val result =
                launchGatewayRuntime(
                    gatewayRepository = GatewayRepositoryImpl(HttpClient(engine), json),
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                )

            assertTrue(result.isSuccess)
            assertEquals("sess-new", result.getOrThrow().handoff.sessionId)
            assertEquals(2, startBodies.size)
            assertTrue("first start reuses the picked runtime", startBodies[0] == null)
            assertTrue("retry spawns isolated", startBodies[1]?.contains("\"new\":true") == true)
        }

    @Test
    fun `refused isolated spawn fails without a second retry`() =
        runTest {
            var starts = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/start")) {
                        starts++
                        respond(
                            """{"runtime":{"id":"runtime-a","status":"running","agentId":"pi-acp"}}""",
                            HttpStatusCode.OK,
                        )
                    } else {
                        respond(
                            """{"error":"runtime_lease_held"}""",
                            HttpStatusCode.Conflict,
                        )
                    }
                }
            val result =
                launchGatewayRuntime(
                    gatewayRepository = GatewayRepositoryImpl(HttpClient(engine), json),
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                    new = true,
                )

            assertTrue(result.isFailure)
            assertEquals(1, starts)
        }

    @Test
    fun `non-lease conflict fails without retry`() =
        runTest {
            var starts = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/start")) {
                        starts++
                        respond(
                            """{"runtime":{"id":"runtime-a","status":"running","agentId":"pi-acp"}}""",
                            HttpStatusCode.OK,
                        )
                    } else {
                        respond("""{"error":"device_limit"}""", HttpStatusCode.Conflict)
                    }
                }
            val result =
                launchGatewayRuntime(
                    gatewayRepository = GatewayRepositoryImpl(HttpClient(engine), json),
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                )

            assertTrue(result.isFailure)
            assertEquals(1, starts)
        }

    @Test
    fun `lease-held reattach to own runtime fails without spawning a duplicate`() =
        runTest {
            var starts = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/start")) {
                        starts++
                    }
                    respond("""{"error":"runtime_lease_held"}""", HttpStatusCode.Conflict)
                }
            val result =
                launchGatewayRuntime(
                    gatewayRepository = GatewayRepositoryImpl(HttpClient(engine), json),
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                    reuseRuntimeId = "runtime-own",
                )

            assertTrue(result.isFailure)
            assertEquals(0, starts)
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
        val SOURCE =
            GatewaySource(
                id = "source-1",
                name = "gw",
                scheme = "http",
                host = "gw.local:5788",
                gatewayCredential = "cred",
            )
    }
}
