package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.ChatFileContents
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/** `GET /v1/files` against the gateway's v3 contract: raw bytes, an 8 MiB range, the v2 text/binary split. */
class GatewayFileReadTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `a read sends only the absolute path and asks for the preview range`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo = repo(png, "image/png") { captured = it }

            repo.fetchFile("http", "10.0.0.2:5788", "plain-token", "/home/u/my app/a.png")

            val request = requireNotNull(captured)
            assertEquals("/v1/files", request.url.encodedPath)
            assertEquals(setOf("path"), request.url.parameters.names())
            assertEquals("/home/u/my app/a.png", request.url.parameters["path"])
            assertEquals("bytes=0-8388607", request.headers[HttpHeaders.Range])
        }

    @Test
    fun `binary bytes survive as a blob, NULs included`() =
        runTest {
            val bytes = byteArrayOf(0, 1, 2, 0, -1)

            val file = repo(bytes, "application/octet-stream").fetchFile("http", "gw", "t", "/p/blob.bin")

            assertEquals(Base64.getEncoder().encodeToString(bytes), file.blob)
            assertEquals(null, file.text)
        }

    @Test
    fun `a raster image is a blob even without a NUL, an SVG stays text`() =
        runTest {
            val noNul = byteArrayOf(0x47, 0x49, 0x46, 0x38)

            assertEquals(null, repo(noNul, "image/gif").fetchFile("http", "gw", "t", "/p/a.gif").text)
            assertEquals(
                "<svg/>",
                repo("<svg/>".toByteArray(), "image/svg+xml").fetchFile("http", "gw", "t", "/p/a.svg").text,
            )
        }

    @Test
    fun `source text reads as text whatever MIME its extension maps to`() =
        runTest {
            val source = "export const a = 1\n".toByteArray()

            val file = repo(source, "video/mp2t").fetchFile("http", "gw", "t", "/p/a.ts")

            assertEquals(ChatFileContents(text = "export const a = 1\n", truncated = false), file)
        }

    @Test
    fun `a file larger than the range is truncated`() =
        runTest {
            val head = "abc".toByteArray()
            val repo = repo(head, "text/plain", HttpStatusCode.PartialContent, contentRange = "bytes 0-2/9000000")

            assertEquals(true, repo.fetchFile("http", "gw", "t", "/p/big.txt").truncated)
        }

    @Test
    fun `a missing file fails with the gateway's status`() =
        runTest {
            val repo = repo("""{"error":"file not found"}""".toByteArray(), "application/json", HttpStatusCode.NotFound)

            val error = runCatching { repo.fetchFile("http", "gw", "t", "/p/gone") }.exceptionOrNull()

            assertEquals(404, (error as? GatewayRequestException)?.statusCode)
        }

    @Test
    fun `an empty file returns an empty source preview despite the unsatisfiable range`() =
        runTest {
            val repo =
                repo(
                    "{\"error\":\"invalid or unsatisfiable file range\"}".toByteArray(),
                    "application/json",
                    HttpStatusCode.RequestedRangeNotSatisfiable,
                    contentRange = "bytes */0",
                )

            assertEquals(
                ChatFileContents(text = "", truncated = false),
                repo.fetchFile("http", "gw", "t", "/p/empty.kt"),
            )
        }

    @Test
    fun `an unsatisfiable range on a nonempty file remains an error`() =
        runTest {
            val repo =
                repo(
                    "{\"error\":\"invalid range\"}".toByteArray(),
                    "application/json",
                    HttpStatusCode.RequestedRangeNotSatisfiable,
                    contentRange = "bytes */12",
                )

            val failure = runCatching { repo.fetchFile("http", "gw", "t", "/p/a.kt") }.exceptionOrNull()

            assertEquals(416, (failure as? GatewayRequestException)?.statusCode)
        }

    private fun repo(
        body: ByteArray,
        contentType: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentRange: String? = null,
        onRequest: (HttpRequestData) -> Unit = {},
    ): GatewayRepositoryImpl =
        GatewayRepositoryImpl(
            HttpClient(
                MockEngine { request ->
                    onRequest(request)
                    val headers =
                        listOfNotNull(
                            HttpHeaders.ContentType to listOf(contentType),
                            contentRange?.let { HttpHeaders.ContentRange to listOf(it) },
                        )
                    respond(content = body, status = status, headers = headersOf(*headers.toTypedArray()))
                },
            ),
            Json { ignoreUnknownKeys = true },
        )
}
