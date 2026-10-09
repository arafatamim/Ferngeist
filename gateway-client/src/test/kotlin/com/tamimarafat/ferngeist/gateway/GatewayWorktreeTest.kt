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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Integration tests for the worktree endpoints ([GatewayRepositoryImpl.createWorktree],
 * [GatewayRepositoryImpl.listWorktrees], [GatewayRepositoryImpl.deleteWorktree]) exercising
 * the real HTTP path: request building, the proof-signed endpoint including its query, and
 * decoding of the gateway's exact response shapes.
 */
class GatewayWorktreeTest {
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
    fun `createWorktree posts the repo base and branch and decodes the response`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody =
                        """
                        {
                            "id": "1a2b3c4d",
                            "repo": "/abs/path/to/repo",
                            "path": "/abs/path/to/repo/.worktrees/feat-x",
                            "branch": "feat/x",
                            "baseCommit": "abc123",
                            "createdAt": "2026-10-09T10:00:00Z"
                        }
                        """.trimIndent(),
                ) { captured = it }

            val worktree =
                repo.createWorktree(
                    scheme = "http",
                    host = "10.0.0.2:5788",
                    gatewayCredential = "plain-token",
                    repo = "/abs/path/to/repo",
                    base = "origin/main",
                    branch = "feat/x",
                )

            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/worktrees", request.url.encodedPath)
            assertEquals("Bearer plain-token", request.headers["Authorization"])
            assertEquals(
                """{"repo":"/abs/path/to/repo","base":"origin/main","branch":"feat/x"}""",
                (request.body as TextContent).text,
            )

            assertEquals("1a2b3c4d", worktree.id)
            assertEquals("/abs/path/to/repo", worktree.repo)
            assertEquals("/abs/path/to/repo/.worktrees/feat-x", worktree.path)
            assertEquals("feat/x", worktree.branch)
            assertEquals("abc123", worktree.baseCommit)
            assertEquals("2026-10-09T10:00:00Z", worktree.createdAt)
            assertNull(worktree.ahead)
            assertNull(worktree.dirty)
        }

    @Test
    fun `createWorktree omits a blank base and branch so the gateway defaults apply`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody =
                        """{"id":"a","repo":"/r","path":"/r/.worktrees/ferngeist-1","branch":"ferngeist/1","baseCommit":"c","createdAt":"t"}""",
                ) { captured = it }

            repo.createWorktree("http", "10.0.0.2:5788", "plain-token", "/r", base = "", branch = "  ")

            assertEquals("""{"repo":"/r"}""", (requireNotNull(captured).body as TextContent).text)
        }

    @Test
    fun `listWorktrees gets the worktrees endpoint and decodes ahead and dirty`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody =
                        """
                        [
                            {
                                "id": "1a2b3c4d",
                                "repo": "/r",
                                "path": "/r/.worktrees/feat-x",
                                "branch": "feat/x",
                                "baseCommit": "abc123",
                                "createdAt": "2026-10-09T10:00:00Z",
                                "ahead": 3,
                                "dirty": true
                            }
                        ]
                        """.trimIndent(),
                ) { captured = it }

            val worktrees = repo.listWorktrees("http", "10.0.0.2:5788", "plain-token")

            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/worktrees", request.url.encodedPath)
            assertEquals(1, worktrees.size)
            assertEquals("feat/x", worktrees[0].branch)
            assertEquals(3, worktrees[0].ahead)
            assertEquals(true, worktrees[0].dirty)
        }

    @Test
    fun `listWorktrees decodes an entry with git fields omitted`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody =
                        """[{"id":"a","repo":"/r","path":"/r/.worktrees/a","branch":"a","baseCommit":"c","createdAt":"t"}]""",
                )

            val worktrees = repo.listWorktrees("http", "10.0.0.2:5788", "plain-token")

            assertEquals(1, worktrees.size)
            assertNull(worktrees[0].ahead)
            assertNull(worktrees[0].dirty)
        }

    @Test
    fun `deleteWorktree sends force only when asked`() =
        runTest {
            val endpoints = mutableListOf<String>()
            val repo = repoCapturing { endpoints += it.url.toString() }

            repo.deleteWorktree("http", "10.0.0.2:5788", "plain-token", "1a2b3c4d")
            repo.deleteWorktree("http", "10.0.0.2:5788", "plain-token", "1a2b3c4d", force = true)

            assertEquals(
                listOf(
                    "http://10.0.0.2:5788/v1/worktrees/1a2b3c4d",
                    "http://10.0.0.2:5788/v1/worktrees/1a2b3c4d?force=true",
                ),
                endpoints,
            )
        }

    @Test
    fun `createWorktree surfaces the 409 error envelope`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody = """{"error":"branch feat/x already exists"}""",
                    status = HttpStatusCode.Conflict,
                )

            try {
                repo.createWorktree("http", "10.0.0.2:5788", "plain-token", "/r", branch = "feat/x")
                fail("expected a GatewayRequestException")
            } catch (error: GatewayRequestException) {
                assertEquals(409, error.statusCode)
                assertEquals("branch feat/x already exists", gatewayErrorMessage(error.responseBody))
            }
        }

    @Test
    fun `listWorktrees surfaces a 404 from a gateway without the worktree API`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody = """{"error":"not found"}""",
                    status = HttpStatusCode.NotFound,
                )

            try {
                repo.listWorktrees("http", "10.0.0.2:5788", "plain-token")
                fail("expected a GatewayRequestException")
            } catch (error: GatewayRequestException) {
                assertEquals(404, error.statusCode)
                assertTrue(error.responseBody.orEmpty().contains("not found"))
            }
        }
}
