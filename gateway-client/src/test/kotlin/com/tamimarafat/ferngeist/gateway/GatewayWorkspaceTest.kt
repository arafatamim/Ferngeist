package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.gateway.workspace.GatewayDiffQuery
import com.tamimarafat.ferngeist.gateway.workspace.GatewayLogQuery
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
 * Integration tests for the workspace endpoints ([GatewayRepositoryImpl.fetchGitStatus],
 * [GatewayRepositoryImpl.fetchGitDiff]) exercising the
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

            val status =
                repo.fetchGitStatus(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "10.0.0.2:5788",
                            gatewayCredential = "plain-token",
                        ),
                )

            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/runtimes/rt-1/git/status", request.url.encodedPath)
            assertEquals("Bearer plain-token", request.headers["Authorization"])

            val changed = requireNotNull(status.changed)
            assertEquals("main", status.branch)
            assertEquals(2, status.ahead)
            assertEquals(0, status.behind)
            assertEquals(2, changed.size)
            assertEquals("src/main.kt", changed[0].path)
            assertEquals("M", changed[0].status)
            assertEquals(12, changed[0].added)
            assertEquals(3, changed[0].removed)
            assertEquals("wip.txt", changed[1].path)
            assertEquals("?", changed[1].status)
            assertEquals(1, changed[1].added)
            assertFalse(changed[1].binary)
        }

    @Test
    fun `fetchGitStatus decodes an empty clean-tree response`() =
        runTest {
            // A clean tree arrives as `"changed":null`: the gateway marshals its nil Go slice,
            // not `[]`, so this exact payload is what the chat has to survive.
            val repo =
                repoCapturing(responseBody = """{"branch":"main","ahead":0,"behind":0,"changed":null}""")

            val status =
                repo.fetchGitStatus(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "10.0.0.2:5788",
                            gatewayCredential = "plain-token",
                        ),
                )

            assertEquals("main", status.branch)
            assertNull(status.changed)
        }

    @Test
    fun `workspace calls scope to the ACP session when given one`() =
        runTest {
            val queries = mutableListOf<String?>()
            val repo = repoCapturing(responseBody = """[]""") { queries += it.url.encodedQuery }

            runCatching {
                repo.fetchGitStatus(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "10.0.0.2:5788",
                            gatewayCredential = "plain-token",
                        ),
                    acpSessionId = "s-1",
                )
            }
            repo.fetchGitDiff(
                connection =
                    GatewayWorkspaceConnection(
                        runtimeId = "rt-1",
                        scheme = "http",
                        host = "10.0.0.2:5788",
                        gatewayCredential = "plain-token",
                    ),
                query = GatewayDiffQuery(acpSessionId = "s-1"),
            )

            assertEquals(listOf("acpSessionId=s-1", "acpSessionId=s-1"), queries)
        }

    @Test
    fun `fetchGitDiff sends the path as a query param and decodes the diff`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody = """{"path":"src/main.kt","oldText":"old\n","newText":"new\n","type":"diff"}""",
                ) { captured = it }

            val diffs =
                repo.fetchGitDiff(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "10.0.0.2:5788",
                            gatewayCredential = "plain-token",
                        ),
                    query = GatewayDiffQuery(path = "src/main.kt"),
                )

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

            val diffs =
                repo.fetchGitDiff(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "10.0.0.2:5788",
                            gatewayCredential = "plain-token",
                        ),
                )

            val request = requireNotNull(captured)
            assertEquals("/v1/runtimes/rt-1/git/diff", request.url.encodedPath)
            // No path param → empty query (Ktor represents no query as "" rather than null).
            assertTrue(request.url.encodedQuery.isEmpty())
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

            repo.fetchGitDiff(
                connection =
                    GatewayWorkspaceConnection(
                        runtimeId = "rt-1",
                        scheme = "http",
                        host = "10.0.0.2:5788",
                        gatewayCredential = "plain-token",
                    ),
                query = GatewayDiffQuery(path = "  "),
            )

            val request = requireNotNull(captured)
            assertTrue(request.url.encodedQuery.isEmpty())
        }

    @Test
    fun `fetchGitDiff in commit mode sends the commit and the rename's old path`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(responseBody = """{"path":"b.kt","newText":"","type":"diff"}""") { captured = it }

            repo.fetchGitDiff(
                connection =
                    GatewayWorkspaceConnection(
                        runtimeId = "rt-1",
                        scheme = "http",
                        host = "h",
                        gatewayCredential = "plain-token",
                    ),
                query = GatewayDiffQuery(path = "b.kt", commit = "abc1234", oldPath = "a.kt"),
            )

            assertEquals("path=b.kt&commit=abc1234&oldPath=a.kt", requireNotNull(captured).url.encodedQuery)
        }

    @Test
    fun `fetchGitLog pins the head and decodes a page`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody =
                        """
                        {
                            "head": "0901beff", "hasMore": true, "remoteUrl": "git@github.com:o/r.git",
                            "baseCommit": "feabf553",
                            "commits": [{
                                "sha": "0901beff", "parents": ["feabf553"], "subject": "fix: x", "body": "",
                                "authorName": "A", "authorEmail": "a@x", "authorTime": 1792000000,
                                "commitTime": 1792000500, "pushed": true,
                                "refs": [{"name": "main", "kind": "branch", "isHead": true}]
                            }]
                        }
                        """.trimIndent(),
                ) { captured = it }

            val log =
                repo.fetchGitLog(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "h",
                            gatewayCredential = "plain-token",
                        ),
                    query = GatewayLogQuery(head = "0901beff", skip = 50, limit = 50),
                )

            val request = requireNotNull(captured)
            assertEquals("/v1/runtimes/rt-1/git/log", request.url.encodedPath)
            assertEquals("head=0901beff&skip=50&limit=50", request.url.encodedQuery)
            assertTrue(log.hasMore)
            assertEquals(1792000000L, log.commits[0].authorTime)
            assertTrue(log.commits[0].pushed)
            assertEquals("branch", log.commits[0].refs[0].kind)
        }

    @Test
    fun `fetchGitCommit puts the sha in the path and decodes renamed files`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody = """{"sha":"abc","changed":[{"path":"b.kt","status":"R","oldPath":"a.kt"}]}""",
                ) { captured = it }

            val files =
                repo.fetchGitCommit(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "h",
                            gatewayCredential = "plain-token",
                        ),
                    sha = "abc",
                )

            assertEquals("/v1/runtimes/rt-1/git/commits/abc", requireNotNull(captured).url.encodedPath)
            assertEquals("a.kt", files.changed[0].oldPath)
        }

    @Test
    fun `workspace endpoints sign requests when the credential carries a proof key`() =
        runTest {
            val proof = GatewayProofAuth.generateProofKey()
            val credential = GatewayProofAuth.encodeStoredCredential("tok-1", proof.privateKey)
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(responseBody = """{"branch":"main","ahead":0,"behind":0,"changed":[]}""") {
                    captured =
                        it
                }

            repo.fetchGitStatus(
                connection =
                    GatewayWorkspaceConnection(
                        runtimeId = "rt-1",
                        scheme = "http",
                        host = "10.0.0.2:5788",
                        gatewayCredential = credential,
                    ),
            )

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
                repo.fetchGitStatus(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "10.0.0.2:5788",
                            gatewayCredential = "plain-token",
                        ),
                )
                fail("expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("422"))
                assertTrue(e.message.orEmpty().contains("not a git repository"))
            }
        }

    @Test
    fun `fetchStatus accepts a matching protocol version`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody =
                        """{"name":"gw","version":"dev","protocolVersion":"v2","remote":{"configured":true}}""",
                )

            val status = repo.fetchStatus("http", "10.0.0.2:5788")

            assertEquals("v2", status.protocolVersion)
        }

    @Test
    fun `fetchStatus rejects the removed v1 protocol version`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody =
                        """{"name":"gw","version":"old","protocolVersion":"v1","remote":{"configured":true}}""",
                )

            try {
                repo.fetchStatus("http", "10.0.0.2:5788")
                fail("expected GatewayProtocolMismatchException")
            } catch (e: GatewayProtocolMismatchException) {
                assertTrue(e.message.orEmpty().contains("v1"))
                assertTrue(e.message.orEmpty().contains("v2"))
            }
        }

    @Test
    fun `fetchStatus rejects an unsupported protocol version`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody =
                        """{"name":"gw","version":"old","protocolVersion":"v0","remote":{"configured":true}}""",
                )

            try {
                repo.fetchStatus("http", "10.0.0.2:5788")
                fail("expected GatewayProtocolMismatchException")
            } catch (e: GatewayProtocolMismatchException) {
                assertTrue(e.message.orEmpty().contains("v0"))
                assertTrue(e.message.orEmpty().contains("v2"))
            }
        }

    @Test
    fun `fetchStatus rejects a gateway that omits the protocol version`() =
        runTest {
            val repo =
                repoCapturing(
                    responseBody = """{"name":"gw","version":"old","remote":{"configured":true}}""",
                )

            try {
                repo.fetchStatus("http", "10.0.0.2:5788")
                fail("expected GatewayProtocolMismatchException")
            } catch (e: GatewayProtocolMismatchException) {
                assertTrue(e.message.orEmpty().contains("null"))
            }
        }

    @Test
    fun `compare is sent as a query param on status, diff and log, and the status echo decodes`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repo =
                repoCapturing(
                    responseBody = """{"branch":"feat","compare":"refs/heads/main","changed":[]}""",
                ) { requests += it }
            val ref = "refs/heads/main"

            val status =
                repo.fetchGitStatus(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "h",
                            gatewayCredential = "t",
                        ),
                    compare = ref,
                )
            runCatching {
                repo.fetchGitDiff(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "h",
                            gatewayCredential = "t",
                        ),
                    query = GatewayDiffQuery(path = "a.kt", compare = ref),
                )
            }
            runCatching {
                repo.fetchGitLog(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "h",
                            gatewayCredential = "t",
                        ),
                    query = GatewayLogQuery(head = null, skip = 0, limit = 50, compare = ref, beyond = 1),
                )
            }

            assertEquals(ref, status.compare)
            assertEquals(listOf(ref, ref, ref), requests.map { it.url.parameters["compare"] })
            // Only the log asks for the target's commits.
            assertEquals(listOf(null, null, "1"), requests.map { it.url.parameters["beyond"] })
        }

    @Test
    fun `a log page decodes the base commit, and sends no beyond by default`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody = """{"head":"h","commits":[],"baseCommit":"abc1234"}""",
                ) { captured = it }

            val log =
                repo.fetchGitLog(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "h",
                            gatewayCredential = "t",
                        ),
                    query = GatewayLogQuery(head = null, skip = 0, limit = 50),
                )

            assertEquals("abc1234", log.baseCommit)
            assertNull(requireNotNull(captured).url.parameters["beyond"])
        }

    @Test
    fun `no compare param is sent, and no echo decodes, for a plain status`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo = repoCapturing(responseBody = """{"branch":"main"}""") { captured = it }

            val status =
                repo.fetchGitStatus(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "h",
                            gatewayCredential = "t",
                        ),
                )

            assertNull(requireNotNull(captured).url.parameters["compare"])
            assertNull(status.compare)
        }

    @Test
    fun `fetchGitRefs hits the refs endpoint and decodes the picker data`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo =
                repoCapturing(
                    responseBody =
                        """{"default":"main","refs":[{"name":"main","kind":"branch","isHead":true},""" +
                            """{"name":"origin/main","kind":"remote"}]}""",
                ) { captured = it }

            val refs =
                repo.fetchGitRefs(
                    connection =
                        GatewayWorkspaceConnection(
                            runtimeId = "rt-1",
                            scheme = "http",
                            host = "h",
                            gatewayCredential = "t",
                        ),
                )

            assertEquals("/v1/runtimes/rt-1/git/refs", requireNotNull(captured).url.encodedPath)
            assertEquals("main", refs.default)
            assertEquals(listOf("main", "origin/main"), refs.refs.map { it.name })
            assertTrue(refs.refs[0].isHead)
        }
}
