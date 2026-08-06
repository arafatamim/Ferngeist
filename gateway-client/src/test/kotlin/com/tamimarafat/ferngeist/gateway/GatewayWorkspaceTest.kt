package com.tamimarafat.ferngeist.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Integration tests for the workspace endpoints ([GatewayRepositoryImpl.fetchWorkspaceFile],
 * [GatewayRepositoryImpl.fetchGitStatus], [GatewayRepositoryImpl.fetchGitDiff]) exercising the
 * real HTTP path: request building, query-param encoding (which is part of the proof-signed
 * endpoint), and JSON decoding of the gateway's exact response shapes.
 */
class GatewayWorkspaceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun repoCapturing(
        responseBody: String = "",
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: (HttpRequestData) -> Unit = {},
    ): GatewayRepositoryImpl {
        val engine =
            MockEngine { request ->
                onRequest(request)
                respond(content = responseBody, status = status)
            }
        return GatewayRepositoryImpl(HttpClient(engine), json)
    }

    @Test
    fun `fetchGitStatus hits the git status endpoint with auth and decodes the response`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody =
                        """
                        {
                            "branch": "main",
                            "ahead": 2,
                            "behind": 0,
                            "changed": [
                                {"path": "src/main.kt", "status": "M", "added": 12, "removed": 3, "binary": false},
                                {"path": "wip.txt", "status": "?", "added": 1, "removed": 0, "binary": false}
                            ]
                        }
                        """.trimIndent(),
                ) { captured = it }

            val status = repo.fetchGitStatus("http", "10.0.0.2:5788", "plain-token", "rt-1")

            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/runtimes/rt-1/git/status", request.url.encodedPath)
            assertEquals("Bearer plain-token", request.headers["Authorization"])

            assertEquals("main", status.branch)
            assertEquals(2, status.ahead)
            assertEquals(0, status.behind)
            assertEquals(2, status.changed.size)
            assertEquals("src/main.kt", status.changed[0].path)
            assertEquals("M", status.changed[0].status)
            assertEquals(12, status.changed[0].added)
            assertEquals(3, status.changed[0].removed)
            assertEquals("wip.txt", status.changed[1].path)
            assertEquals("?", status.changed[1].status)
            assertEquals(1, status.changed[1].added)
            assertFalse(status.changed[1].binary)
        }

    @Test
    fun `fetchGitStatus decodes an empty clean-tree response`() =
        runTest {
            val repo =
                repoCapturing(responseBody = """{"branch":"main","ahead":0,"behind":0,"changed":[]}""")

            val status = repo.fetchGitStatus("http", "10.0.0.2:5788", "plain-token", "rt-1")

            assertEquals("main", status.branch)
            assertTrue(status.changed.isEmpty())
        }

    @Test
    fun `fetchGitDiff sends the path as a query param and decodes the diff`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody = """{"path":"src/main.kt","oldText":"old\n","newText":"new\n","type":"diff"}""",
                ) { captured = it }

            val diffs = repo.fetchGitDiff("http", "10.0.0.2:5788", "plain-token", "rt-1", path = "src/main.kt")

            val request = requireNotNull(captured)
            assertEquals("/v1/runtimes/rt-1/git/diff", request.url.encodedPath)
            assertEquals("path=src%2Fmain.kt", request.url.encodedQuery)
            assertEquals(1, diffs.size)
            assertEquals("src/main.kt", diffs[0].path)
            assertEquals("old\n", diffs[0].oldText)
            assertEquals("new\n", diffs[0].newText)
        }

    @Test
    fun `fetchGitDiff without a path decodes the whole-tree array`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody =
                        """[
                            {"path":"a.txt","oldText":"x\n","newText":"y\n","type":"diff"},
                            {"path":"wip.txt","newText":"wip\n","type":"diff"}
                        ]""",
                ) { captured = it }

            val diffs = repo.fetchGitDiff("http", "10.0.0.2:5788", "plain-token", "rt-1")

            val request = requireNotNull(captured)
            assertEquals("/v1/runtimes/rt-1/git/diff", request.url.encodedPath)
            // No path param → empty query (Ktor represents no query as "" rather than null).
            assertTrue(request.url.encodedQuery.isNullOrEmpty())
            assertEquals(2, diffs.size)
            // New/untracked files have no oldText.
            assertNull(diffs[1].oldText)
        }

    @Test
    fun `fetchGitDiff treats a blank path as the worktree diff`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(responseBody = """[]""") { captured = it }

            repo.fetchGitDiff("http", "10.0.0.2:5788", "plain-token", "rt-1", path = "  ")

            val request = requireNotNull(captured)
            assertTrue(request.url.encodedQuery.isNullOrEmpty())
        }

    @Test
    fun `fetchWorkspaceFile sends the path query and decodes a text file`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody =
                        """
                        {
                            "text": "hello world\n",
                            "uri": "file:///repo/README.md",
                            "mimeType": "text/plain; charset=utf-8",
                            "size": 13,
                            "truncated": false
                        }
                        """.trimIndent(),
                ) { captured = it }

            val file = repo.fetchWorkspaceFile("http", "10.0.0.2:5788", "plain-token", "rt-1", "README.md")

            val request = requireNotNull(captured)
            assertEquals("/v1/runtimes/rt-1/files", request.url.encodedPath)
            assertEquals("path=README.md", request.url.encodedQuery)
            val text = file as GatewayFileRead.Text
            assertEquals("hello world\n", text.contents.text)
            assertEquals("file:///repo/README.md", text.contents.uri)
            assertFalse(text.truncated)
        }

    @Test
    fun `fetchWorkspaceFile decodes a truncated response`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody = """{"text":"...","uri":"file:///repo/big.txt","size":1048576,"truncated":true}""",
                )

            val file = repo.fetchWorkspaceFile("http", "10.0.0.2:5788", "plain-token", "rt-1", "big.txt")

            val text = file as GatewayFileRead.Text
            assertTrue(text.truncated)
            assertEquals(1048576, text.size)
        }

    @Test
    fun `fetchWorkspaceFile decodes a binary response`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody = """{"blob":"AAECAw==","uri":"file:///repo/clip.png","mimeType":"image/png","size":4,"truncated":false}""",
                )

            val file = repo.fetchWorkspaceFile("http", "10.0.0.2:5788", "plain-token", "rt-1", "clip.png")

            val binary = file as GatewayFileRead.Binary
            assertEquals("AAECAw==", binary.contents.blob)
            assertEquals("image/png", binary.contents.mimeType)
            assertEquals(4, binary.size)
            assertFalse(binary.truncated)
        }

    @Test
    fun `workspace endpoints sign requests when the credential carries a proof key`() =
        runTest {
            val proof = GatewayProofAuth.generateProofKey()
            val credential = GatewayProofAuth.encodeStoredCredential("tok-1", proof.privateKey)
            var captured: HttpRequestData? = null
            val repo = repoCapturing(responseBody = """{"branch":"main","ahead":0,"behind":0,"changed":[]}""") { captured = it }

            repo.fetchGitStatus("http", "10.0.0.2:5788", credential, "rt-1")

            val request = requireNotNull(captured)
            assertEquals("Bearer tok-1", request.headers["Authorization"])
            assertTrue(request.headers["X-Ferngeist-Proof-Signature"].orEmpty().isNotBlank())
            assertTrue(request.headers["X-Ferngeist-Proof-Timestamp"].orEmpty().isNotBlank())
            assertTrue(request.headers["X-Ferngeist-Proof-Nonce"].orEmpty().isNotBlank())
        }

    @Test
    fun `workspace endpoints throw on a non-success response`() =
        runTest {
            val repo = repoCapturing(status = HttpStatusCode.UnprocessableEntity, responseBody = "not a git repository")

            try {
                repo.fetchGitStatus("http", "10.0.0.2:5788", "plain-token", "rt-1")
                fail("expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("422"))
                assertTrue(e.message.orEmpty().contains("not a git repository"))
            }
        }
}
