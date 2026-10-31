package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** `GET /files` against the gateway's exact response shapes: a workspace image arrives as a blob. */
class GatewayFileReadTest {
    private val connection = GatewayWorkspaceConnection("rt_1", "http", "10.0.0.2:5788", "plain-token")

    @Test
    fun `a binary read decodes the blob and scopes the request to the session`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repo(
                    """
                    {"blob":"iVBORw0KGgo=","uri":"file:///p/a.png","mimeType":"image/png","size":8,"truncated":false}
                    """.trimIndent(),
                ) { captured = it }

            val file = repo.fetchFile(connection, path = "docs/a.png", acpSessionId = "ses_a")

            assertEquals(GatewayFileContents(text = null, blob = "iVBORw0KGgo=", truncated = false), file)
            assertEquals("/v1/runtimes/rt_1/files", captured?.url?.encodedPath)
            assertEquals("docs/a.png", captured?.url?.parameters?.get("path"))
            assertEquals("ses_a", captured?.url?.parameters?.get("acpSessionId"))
        }

    @Test
    fun `an SVG keeps its text and an oversized read reports truncation`() =
        runTest {
            val svg = repo("""{"text":"<svg/>","mimeType":"image/svg+xml","size":6,"truncated":false}""")
            val big = repo("""{"blob":"AAAA","mimeType":"image/png","size":9000000,"truncated":true}""")

            assertEquals("<svg/>", svg.fetchFile(connection, "a.svg").text)
            assertEquals(true, big.fetchFile(connection, "big.png").truncated)
        }

    private fun repo(
        body: String,
        onRequest: (HttpRequestData) -> Unit = {},
    ): GatewayRepositoryImpl =
        GatewayRepositoryImpl(
            HttpClient(
                MockEngine { request ->
                    onRequest(request)
                    respond(content = body, status = HttpStatusCode.OK)
                },
            ),
            Json { ignoreUnknownKeys = true },
        )
}
