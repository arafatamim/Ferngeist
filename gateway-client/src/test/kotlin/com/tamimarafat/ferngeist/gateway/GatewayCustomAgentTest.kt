package com.tamimarafat.ferngeist.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCustomAgentTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var captured: HttpRequestData? = null

    private fun repo(
        responseBody: String = "{}",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): GatewayRepositoryImpl {
        val engine =
            MockEngine { request ->
                captured = request
                respond(content = responseBody, status = status)
            }
        return GatewayRepositoryImpl(HttpClient(engine), json)
    }

    private val createdAgent =
        """{"id":"custom-my-agent","displayName":"My Agent","detected":true,"manifestValid":true,""" +
            """"security":{"allowsRemoteStart":true},"source":"custom"}"""

    @Test
    fun `create posts the agent and decodes it`() =
        runTest {
            val agent =
                repo(createdAgent, HttpStatusCode.Created).createCustomAgent(
                    scheme = "http",
                    host = "gw.local:5788",
                    gatewayCredential = "token",
                    displayName = "My Agent",
                    command = "my-agent",
                    args = listOf("--acp"),
                    hint = "note",
                )
            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/agents/custom", request.url.encodedPath)
            assertEquals(
                """{"displayName":"My Agent","command":"my-agent","args":["--acp"],"hint":"note"}""",
                (request.body as TextContent).text,
            )
            assertEquals("Bearer token", request.headers["Authorization"])
            assertEquals("custom-my-agent", agent.id)
            assertEquals("custom", agent.source)
            assertTrue(agent.detected)
        }

    @Test
    fun `create omits empty args and hint`() =
        runTest {
            repo(createdAgent, HttpStatusCode.Created).createCustomAgent(
                scheme = "http",
                host = "gw.local:5788",
                gatewayCredential = "token",
                displayName = "My Agent",
                command = "my-agent",
                args = emptyList(),
                hint = "",
            )
            assertEquals(
                """{"displayName":"My Agent","command":"my-agent"}""",
                (requireNotNull(captured).body as TextContent).text,
            )
        }

    @Test
    fun `delete targets the custom agent path`() =
        runTest {
            repo().deleteCustomAgent("http", "gw.local:5788", "token", "custom-my-agent")
            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/v1/agents/custom/custom-my-agent", request.url.encodedPath)
            assertEquals("Bearer token", request.headers["Authorization"])
        }

    @Test
    fun `stop posts to the agent stop path`() =
        runTest {
            repo().stopAgent("http", "gw.local:5788", "token", "custom-my-agent")
            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/agents/custom-my-agent/stop", request.url.encodedPath)
            assertEquals("Bearer token", request.headers["Authorization"])
        }

    @Test
    fun `surfaces the gateway error body on refusal`() =
        runTest {
            val error =
                runCatching {
                    repo("""{"error":"custom agent limit reached (50)"}""", HttpStatusCode.Conflict)
                        .createCustomAgent("http", "gw.local:5788", "token", "N", "c", emptyList(), "")
                }.exceptionOrNull() as GatewayRequestException
            assertEquals(409, error.statusCode)
            assertEquals("custom agent limit reached (50)", gatewayErrorMessage(error.responseBody))
        }

    @Test
    fun `surfaces the gateway error body when a used agent refuses deletion`() =
        runTest {
            val error =
                runCatching {
                    repo("""{"error":"agent has running runtimes"}""", HttpStatusCode.Conflict)
                        .deleteCustomAgent("http", "gw.local:5788", "token", "custom-my-agent")
                }.exceptionOrNull() as GatewayRequestException
            assertEquals(409, error.statusCode)
            assertEquals("agent has running runtimes", gatewayErrorMessage(error.responseBody))
        }
}
