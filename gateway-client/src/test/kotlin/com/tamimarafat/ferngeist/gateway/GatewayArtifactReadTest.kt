package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A whole-file `GET /v1/files` download (exact bytes, the host's type, revalidation, and no partial
 * or redirected success), and `POST /v1/files/stat`.
 */
class GatewayArtifactReadTest {
    private val path = "/tmp/ferngeist-elicitation-probe/live-full-history-test.mp4"

    @Test
    fun `a download streams every byte and reports the host's type and size`() =
        runTest {
            val bytes = ByteArray(70_000) { (it % 256).toByte() }
            var captured: HttpRequestData? = null
            val repo =
                repo { request ->
                    captured = request
                    respond(
                        bytes,
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.ContentType to listOf("video/mp4"),
                            HttpHeaders.ContentLength to listOf("70000"),
                            HttpHeaders.ETag to listOf("\"v1\""),
                        ),
                    )
                }
            val out = ByteArrayOutputStream()

            val metadata = requireNotNull(repo.downloadFile(target("gw.local:5788", "plain-token"), path, out, null))

            assertArrayEquals(bytes, out.toByteArray())
            assertEquals("video/mp4", metadata.mimeType)
            assertEquals(70_000L, metadata.size)
            assertEquals("\"v1\"", metadata.etag)
            val request = requireNotNull(captured)
            assertEquals("/v1/files", request.url.encodedPath)
            assertEquals(path, request.url.parameters["path"])
            assertNull("a download is the whole file", request.headers[HttpHeaders.Range])
        }

    @Test
    fun `an unchanged file answers the etag with 304, writing nothing`() =
        runTest {
            var sent: String? = null
            val repo =
                repo { request ->
                    sent = request.headers[HttpHeaders.IfNoneMatch]
                    respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, "\"v1\""))
                }
            val out = ByteArrayOutputStream()

            val metadata = repo.downloadFile(target("gw"), path, out, "\"v1\"")

            assertEquals("\"v1\"", sent)
            assertNull(metadata)
            assertEquals(0, out.size())
        }

    @Test
    fun `stat posts the paths in batches the gateway accepts and keeps each entry in order`() =
        runTest {
            val bodies = mutableListOf<String>()
            val repo =
                repo { request ->
                    val body = (request.body as TextContent).text
                    bodies += body
                    val paths = Json.decodeFromString<GatewayPathsRequest>(body).paths
                    val files =
                        paths.joinToString(",") {
                            if (it.endsWith("gone.pdf")) {
                                """{"type":"resource_link","uri":"file://$it","name":"gone.pdf","error":"not_found"}"""
                            } else {
                                """{"type":"resource_link","uri":"file://$it","name":"a","mimeType":"video/mp4","size":12}"""
                            }
                        }
                    respond("""{"files":[$files]}""", HttpStatusCode.OK)
                }
            val paths = List(64) { "/tmp/$it.mp4" } + "/tmp/gone.pdf"

            val stats = repo.statFiles(target("gw"), paths)

            assertEquals(listOf(64, 1), bodies.map { Json.decodeFromString<GatewayPathsRequest>(it).paths.size })
            assertEquals(12L, stats.first().size)
            assertEquals("not_found", stats.last().error)
            assertNull(stats.last().size)
        }

    @Test
    fun `gateway refusals fail with their status and write nothing`() =
        runTest {
            listOf(HttpStatusCode.NotFound, HttpStatusCode.PayloadTooLarge, HttpStatusCode.TooManyRequests).forEach {
                val repo = repo { _ -> respond("""{"error":"no"}""", it) }
                val out = ByteArrayOutputStream()

                val error = runCatching { repo.downloadFile(target("gw"), path, out, null) }.exceptionOrNull()

                assertEquals(it.value, (error as? GatewayRequestException)?.statusCode)
                assertEquals(0, out.size())
            }
        }

    @Test
    fun `a body shorter than its length fails`() =
        runTest {
            val repo =
                repo { _ ->
                    respond(ByteArray(4), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "10"))
                }

            assertNotNull(
                runCatching { repo.downloadFile(target("gw"), path, ByteArrayOutputStream(), null) }.exceptionOrNull(),
            )
        }

    @Test
    fun `a redirect off the gateway fails without carrying the credential`() =
        runTest {
            var elsewhere: HttpRequestData? = null
            val repo =
                repo { request ->
                    if (request.url.host == "gw.local") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://evil.example/x"))
                    } else {
                        elsewhere = request
                        respond(ByteArray(3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "3"))
                    }
                }
            val out = ByteArrayOutputStream()

            val error =
                runCatching {
                    repo.downloadFile(target("gw.local", "plain-token"), path, out, null)
                }.exceptionOrNull()

            assertNotNull(error)
            assertEquals(0, out.size())
            assertNull("a signed file request must never follow a redirect", elsewhere)
        }

    @Test
    fun `a redirect to another port on the gateway host is rejected before sending`() =
        runTest {
            var redirected = false
            val repo =
                repo { request ->
                    if (request.url.port == 5788) {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://gw.local:9000/x"))
                    } else {
                        redirected = true
                        respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "3"))
                    }
                }
            val out = ByteArrayOutputStream()

            val error = runCatching { repo.downloadFile(target("gw.local:5788"), path, out, null) }.exceptionOrNull()

            assertNotNull(error)
            assertEquals(false, redirected)
            assertEquals(0, out.size())
        }

    private fun target(
        host: String,
        credential: String = "t",
    ) = ProjectGitTarget("http", host, credential, cwd = "")

    private fun repo(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GatewayRepositoryImpl(HttpClient(MockEngine(handler)), Json { ignoreUnknownKeys = true })
}
