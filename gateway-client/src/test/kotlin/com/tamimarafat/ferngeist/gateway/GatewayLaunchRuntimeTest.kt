package com.tamimarafat.ferngeist.gateway

import app.cash.turbine.test
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.launch.GatewayRuntimeLauncher
import com.tamimarafat.ferngeist.gateway.launch.ReattachRequest
import com.tamimarafat.ferngeist.gateway.launch.reattachTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A gateway launch is one protocol-v2 connect call: the gateway spawns (or reuses)
 * the agent's runtime and hands back the session to attach to. There is no start
 * stage or runtime pick. The gateway owns the agent process lifetime, and a
 * failed connect surfaces the gateway's own error.
 */
class GatewayLaunchRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a launch connects the agent and returns the v2 handoff`() =
        runTest {
            val requested = mutableListOf<String>()
            val result =
                GatewayRuntimeLauncher(repo(requested), FakeGatewaySources()).launch(SERVER)

            val launched = result.getOrThrow()
            assertEquals(SOURCE, launched.gatewaySource)
            assertEquals("runtime-1", launched.handoff.runtimeId)
            assertEquals("sess-1", launched.handoff.sessionId)
            assertEquals("attach-1", launched.handoff.attachToken)
            // The session list is checked first; this fake answers it with a non-list body.
            assertEquals(listOf("GET /v1/sessions", "POST /v1/agents/pi-acp/connect"), requested)
        }

    @Test
    fun `a launch resumes the agent's existing session instead of connecting`() =
        runTest {
            val requested = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requested += "${request.method.value} ${request.url.encodedPath}"
                    when (request.url.encodedPath) {
                        "/v1/sessions" ->
                            respond(
                                """{"sessions":[
                                {"sessionId":"s-other","runtimeId":"r-other","agentId":"other","status":"active"},
                                {"sessionId":"s-live","runtimeId":"r-live","agentId":"pi-acp","status":"active"},
                                {"sessionId":"s-dead","runtimeId":"r-dead","agentId":"pi-acp","status":"failed"},
                                {"sessionId":"s-old","runtimeId":"r-1","agentId":"pi-acp","status":"disconnected"}
                                ]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> respond("""{"attachToken":"resumed-1"}""", HttpStatusCode.OK)
                    }
                }

            val handoff =
                GatewayRuntimeLauncher(GatewayRepositoryImpl(HttpClient(engine), json), FakeGatewaySources())
                    .launch(SERVER)
                    .getOrThrow()
                    .handoff

            assertEquals("s-old", handoff.sessionId)
            assertEquals("r-1", handoff.runtimeId)
            assertEquals("resumed-1", handoff.attachToken)
            assertEquals("ws://gw.local:5788/v1/acp/r-1", handoff.webSocketUrl)
            assertEquals(listOf("GET /v1/sessions", "POST /v1/sessions/s-old/resume"), requested)
        }

    @Test
    fun `reattach prefers the session running the opened chat over a newer one`() {
        val sessions =
            listOf(
                GatewaySessionSummary("s-mine", "r-1", "pi-acp", "disconnected", "2026-01-01", listOf("chat-a")),
                GatewaySessionSummary("s-newer", "r-2", "pi-acp", "disconnected", "2026-02-01", listOf("chat-b")),
                GatewaySessionSummary("s-live", "r-3", "pi-acp", "active", "2026-03-01", listOf("chat-a")),
            )

        assertEquals("s-mine", reattachTarget(sessions, "pi-acp", ReattachRequest("chat-a", emptySet()))?.sessionId)
        assertEquals(
            "s-newer",
            reattachTarget(sessions, "pi-acp", ReattachRequest("chat-unknown", emptySet()))?.sessionId,
        )
    }

    @Test
    fun `a listing never reattaches a session a restored chat still runs`() {
        val sessions =
            listOf(
                GatewaySessionSummary("s-orphan", "r-1", "pi-acp", "disconnected", "2026-01-01", listOf("gone")),
                GatewaySessionSummary("s-restored", "r-2", "pi-acp", "disconnected", "2026-02-01", listOf("chat-b")),
            )

        assertEquals("s-orphan", reattachTarget(sessions, "pi-acp", ReattachRequest(null, setOf("chat-b")))?.sessionId)
        assertEquals(
            "s-restored",
            reattachTarget(sessions, "pi-acp", ReattachRequest("chat-b", setOf("chat-b")))?.sessionId,
        )
    }

    @Test
    fun `restartRuntime posts to the runtime restart endpoint and parses the v2 response`() =
        runTest {
            val requested = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requested += "${request.method.value} ${request.url.encodedPath}"
                    respond(
                        """{"runtimeId":"runtime-4","sessionId":"sess-7","attachToken":"at-7",""" +
                            """"websocketUrl":"ws://gw.local:5788/v1/acp/runtime-4"}""",
                        HttpStatusCode.OK,
                    )
                }
            val repository = GatewayRepositoryImpl(HttpClient(engine), json)

            val restarted = repository.restartRuntime(SOURCE, "runtime-3", emptyMap(), "target")

            // A restart launches a new runtime; the client must attach to that one.
            assertEquals("runtime-4", restarted.runtimeId)
            assertEquals("sess-7", restarted.sessionId)
            assertEquals("at-7", restarted.attachToken)
            assertEquals(listOf("POST /v1/runtimes/runtime-3/restart"), requested)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connect waits through the end of the download for the launch handoff`() =
        runTest {
            val retries = mutableListOf<Long>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val engine =
                MockEngine.create {
                    this.dispatcher = dispatcher
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/v1/sessions" -> respond("""{"sessions":[]}""", HttpStatusCode.OK)
                            else -> {
                                retries += testScheduler.currentTime
                                when (retries.size) {
                                    1 ->
                                        respond(
                                            """{"error":"installing","code":"agent_installing","bytesReceived":24}""",
                                            HttpStatusCode.ServiceUnavailable,
                                            headersOf(HttpHeaders.RetryAfter, "3"),
                                        )
                                    2 ->
                                        respond(
                                            """{"error":"installing","code":"agent_installing",""" +
                                                """"bytesReceived":42,"bytesTotal":42}""",
                                            HttpStatusCode.ServiceUnavailable,
                                        )
                                    else -> respond(CONNECT_BODY, HttpStatusCode.OK)
                                }
                            }
                        }
                    }
                }
            val repository = GatewayRepositoryImpl(HttpClient(engine), json)
            repository.agentInstallations.test {
                assertTrue(awaitItem().isEmpty())
                val launch =
                    async {
                        GatewayRuntimeLauncher(repository, FakeGatewaySources(), dispatcher)
                            .launch(SERVER)
                    }

                val downloading = awaitItem().getValue("target")
                assertEquals(24L, downloading.bytesReceived)
                assertEquals(null, downloading.bytesTotal)
                val extracting = awaitItem().getValue("target")
                assertEquals(42L, extracting.bytesReceived)
                assertEquals(42L, extracting.bytesTotal)
                assertTrue(awaitItem().isEmpty())
                val handoff = launch.await().getOrThrow().handoff
                assertEquals("sess-1", handoff.sessionId)
                assertEquals(listOf(0L, 3_000L, 5_000L), retries)
            }
        }

    @Test
    fun `a service unavailable response without the installing code fails the launch immediately`() =
        runTest {
            var connectAttempts = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath == "/v1/sessions") {
                        respond("""{"sessions":[]}""", HttpStatusCode.OK)
                    } else if (connectAttempts++ == 0) {
                        respond("""{"error":"session service not available"}""", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond(CONNECT_BODY, HttpStatusCode.OK)
                    }
                }
            val repository = GatewayRepositoryImpl(HttpClient(engine), json)
            val result =
                GatewayRuntimeLauncher(repository, FakeGatewaySources())
                    .launch(SERVER)

            val failure = result.exceptionOrNull() as? GatewayRequestException
            assertEquals(503, failure?.statusCode)
            assertTrue(failure?.message?.contains("session service not available") == true)
        }

    @Test
    fun `cancelling a pending restart clears progress and stops requests`() =
        runTest {
            var restartAttempts = 0
            val engine =
                MockEngine {
                    restartAttempts++
                    respond(
                        """{"error":"installing","code":"agent_installing","bytesReceived":10}""",
                        HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.RetryAfter, "60"),
                    )
                }
            val repository = GatewayRepositoryImpl(HttpClient(engine), json)
            repository.agentInstallations.test {
                assertTrue(awaitItem().isEmpty())
                val restart =
                    async {
                        repository.restartRuntime(SOURCE, "runtime-3", emptyMap(), "target")
                    }
                assertEquals(10L, awaitItem().getValue("target").bytesReceived)

                restart.cancelAndJoin()

                assertTrue(awaitItem().isEmpty())
                assertEquals(1, restartAttempts)
            }
        }

    @Test
    fun `a connect failure surfaces the gateway error text verbatim`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"error":"agent process exited before the runtime was ready"}""",
                        HttpStatusCode.BadGateway,
                    )
                }
            val result =
                GatewayRuntimeLauncher(GatewayRepositoryImpl(HttpClient(engine), json), FakeGatewaySources())
                    .launch(SERVER)

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(
                "the agent's exit error must reach the caller: $message",
                message.contains("agent process exited before the runtime was ready"),
            )
            assertFalse("a failed launch must not report success", result.isSuccess)
        }

    @Test
    fun `reopening a chat on a gateway with the one-call attach makes only that call`() =
        runTest {
            val requested = mutableListOf<String>()
            val attached = CONNECT_BODY.dropLast(1) + ""","epoch":"e7"}"""
            val engine =
                MockEngine { request ->
                    requested += "${request.method.value} ${request.url.encodedPath}"
                    respond(attached, HttpStatusCode.OK)
                }

            val launched =
                GatewayRuntimeLauncher(GatewayRepositoryImpl(HttpClient(engine), json), FakeGatewaySources())
                    .launch(
                        server = SERVER,
                        requireSupportedProtocol = true,
                        acpSessionId = "chat-1",
                    ).getOrThrow()

            assertEquals(listOf("POST /v1/sessions/attach"), requested)
            assertEquals("e7", launched.epoch)
        }

    @Test
    fun `an attach reporting an unsupported protocol fails as a protocol mismatch`() =
        runTest {
            val attached = CONNECT_BODY.dropLast(1) + ""","protocolVersion":"v9"}"""
            val engine = MockEngine { respond(attached, HttpStatusCode.OK) }

            val result =
                GatewayRuntimeLauncher(GatewayRepositoryImpl(HttpClient(engine), json), FakeGatewaySources())
                    .launch(
                        server = SERVER,
                        requireSupportedProtocol = true,
                        acpSessionId = "chat-1",
                    )

            assertTrue(result.exceptionOrNull() is GatewayProtocolMismatchException)
        }

    @Test
    fun `reopening a chat on an older gateway falls back to the old launch`() =
        runTest {
            val requested = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requested += "${request.method.value} ${request.url.encodedPath}"
                    if (request.url.encodedPath == "/v1/sessions/attach") {
                        respond("404 page not found", HttpStatusCode.NotFound)
                    } else {
                        respond(CONNECT_BODY, HttpStatusCode.OK)
                    }
                }

            val handoff =
                GatewayRuntimeLauncher(GatewayRepositoryImpl(HttpClient(engine), json), FakeGatewaySources())
                    .launch(SERVER, acpSessionId = "chat-1")
                    .getOrThrow()
                    .handoff

            assertEquals(
                listOf("POST /v1/sessions/attach", "GET /v1/sessions", "POST /v1/agents/pi-acp/connect"),
                requested,
            )
            assertEquals("sess-1", handoff.sessionId)
        }

    @Test
    fun `an attach the gateway itself rejects as not found fails instead of taking the old launch`() =
        runTest {
            val requested = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requested += "${request.method.value} ${request.url.encodedPath}"
                    respond("""{"error":"agent not found"}""", HttpStatusCode.NotFound)
                }

            val result =
                GatewayRuntimeLauncher(GatewayRepositoryImpl(HttpClient(engine), json), FakeGatewaySources())
                    .launch(SERVER, acpSessionId = "chat-1")

            assertEquals(listOf("POST /v1/sessions/attach"), requested)
            assertFalse(result.isSuccess)
        }

    private fun repo(requested: MutableList<String>): GatewayRepositoryImpl {
        val engine =
            MockEngine { request ->
                requested += "${request.method.value} ${request.url.encodedPath}"
                respond(CONNECT_BODY, HttpStatusCode.OK)
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
        val SOURCE =
            GatewaySource(
                id = "source-1",
                name = "gw",
                scheme = "http",
                host = "gw.local:5788",
                gatewayCredential = "cred",
            )

        val SERVER =
            LaunchableTarget.GatewayAgent(
                binding =
                    GatewayAgentBinding(
                        id = "target",
                        name = "Pi",
                        gatewaySourceId = SOURCE.id,
                        agentId = "pi-acp",
                    ),
                gatewaySource = SOURCE,
            )

        val CONNECT_BODY =
            """
            {"runtimeId":"runtime-1","sessionId":"sess-1","attachToken":"attach-1",
             "websocketUrl":"ws://gw.local:5788/v1/runtimes/runtime-1/ws"}
            """.trimIndent()
    }
}
