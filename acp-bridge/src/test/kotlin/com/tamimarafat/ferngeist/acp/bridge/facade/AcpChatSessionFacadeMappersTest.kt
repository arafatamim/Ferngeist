package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.GitHistoryHeadGoneException
import com.tamimarafat.ferngeist.core.model.GitHistoryUnsupportedException
import com.tamimarafat.ferngeist.core.model.GitRefsUnsupportedException
import com.tamimarafat.ferngeist.gateway.GatewayChangedFile
import com.tamimarafat.ferngeist.gateway.GatewayGitCommit
import com.tamimarafat.ferngeist.gateway.GatewayGitLog
import com.tamimarafat.ferngeist.gateway.GatewayGitRef
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gateway's wire format, configured as the app configures it. */
private val gatewayJson = Json { ignoreUnknownKeys = true }

/** The gateway working tree's shape, mapped into the chat domain the feature layer holds. */
class AcpChatSessionFacadeMappersTest {
    @Test
    fun `git status carries branch, ahead-behind, and every changed file field`() {
        val mapped =
            GatewayGitStatus(
                branch = "main",
                ahead = 2,
                behind = 1,
                changed =
                    listOf(
                        GatewayChangedFile(
                            path = "src/Main.kt",
                            status = "M",
                            added = 7,
                            removed = 3,
                            binary = true,
                            isDir = true,
                        ),
                    ),
            ).toChatGitStatus()

        assertEquals("main", mapped.branch)
        assertEquals(2, mapped.ahead)
        assertEquals(1, mapped.behind)
        val file = mapped.changed.single()
        assertEquals("src/Main.kt", file.path)
        assertEquals("M", file.status)
        assertEquals(7, file.added)
        assertEquals(3, file.removed)
        assertTrue(file.binary)
        assertTrue(file.isDir)
    }

    @Test
    fun `an empty status maps to empty defaults`() {
        val mapped = GatewayGitStatus().toChatGitStatus()

        assertEquals("", mapped.branch)
        assertEquals(0, mapped.ahead)
        assertEquals(0, mapped.behind)
        assertTrue(mapped.changed.isEmpty())
    }

    @Test
    fun `a clean tree's null changed list decodes and maps to empty`() {
        val status =
            gatewayJson.decodeFromString<GatewayGitStatus>(
                """{"branch":"main","ahead":0,"behind":0,"changed":null}""",
            )

        val mapped = status.toChatGitStatus()

        assertEquals("main", mapped.branch)
        assertTrue(mapped.changed.isEmpty())
    }

    @Test
    fun `a rebased commit keeps its own commit time`() {
        val commit = GatewayGitCommit(sha = "abc", authorTime = 2, commitTime = 5).toChatGitCommit()

        assertEquals(2000L, commit.authorTimeMillis)
        assertEquals(5000L, commit.commitTimeMillis)
    }

    @Test
    fun `a log page carries the walk's total, null when the gateway omits it`() {
        assertEquals(7, GatewayGitLog(total = 7).toChatGitLogPage().total)
        assertNull(GatewayGitLog().toChatGitLogPage().total)
    }

    @Test
    fun `a log page carries the base the workspace is read against, null when there is none`() {
        assertEquals("feabf553", GatewayGitLog(baseCommit = "feabf553").toChatGitLogPage().base)
        assertNull(GatewayGitLog().toChatGitLogPage().base)
    }

    @Test
    fun `a log page maps seconds to millis, ref kinds, and blank head or remote to null`() {
        val page =
            GatewayGitLog(
                commits =
                    listOf(
                        GatewayGitCommit(
                            sha = "abc",
                            authorTime = 2,
                            pushed = true,
                            refs =
                                listOf(
                                    GatewayGitRef("HEAD", "head"),
                                    GatewayGitRef("origin/main", "remote"),
                                    GatewayGitRef("v1", "tag"),
                                    GatewayGitRef("main", "branch", isHead = true),
                                    GatewayGitRef("x", "stash"),
                                ),
                        ),
                    ),
            ).toChatGitLogPage()

        assertNull(page.head)
        assertNull(page.remoteUrl)
        val commit = page.commits.single()
        assertEquals(2000L, commit.authorTimeMillis)
        // An older gateway sends no commit time, so it falls back to the author's.
        assertEquals(2000L, commit.commitTimeMillis)
        assertTrue(commit.isPushed)
        assertEquals(
            listOf(
                ChatGitRef.Kind.HEAD,
                ChatGitRef.Kind.REMOTE,
                ChatGitRef.Kind.TAG,
                ChatGitRef.Kind.BRANCH,
                ChatGitRef.Kind.BRANCH,
            ),
            commit.refs.map { it.kind },
        )
        assertTrue(commit.refs[3].isHead)
    }

    @Test
    fun `a log failure tells an old gateway and a vanished head from other errors`() {
        val unknownRoute = GatewayRequestException(404, "404", responseBody = "404 page not found\n")
        val noSession = GatewayRequestException(404, "404", responseBody = """{"error":"no session"}""")
        val badRequest = GatewayRequestException(400, "400", responseBody = """{"error":"unknown head"}""")

        assertTrue(gitLogFailure(unknownRoute, pinnedHead = false) is GitHistoryUnsupportedException)
        assertSame(noSession, gitLogFailure(noSession, pinnedHead = false))
        assertTrue(gitLogFailure(badRequest, pinnedHead = true) is GitHistoryHeadGoneException)
        assertSame(badRequest, gitLogFailure(badRequest, pinnedHead = false))
    }

    @Test
    fun `gitRefsFailure treats only the plain-text unknown-route 404 as unsupported`() {
        val unknownRoute = GatewayRequestException(404, "404", responseBody = "404 page not found")
        val runtimeGone = GatewayRequestException(404, "404", responseBody = """{"error":"unknown runtime"}""")

        assertTrue(gitRefsFailure(unknownRoute) is GitRefsUnsupportedException)
        assertSame(runtimeGone, gitRefsFailure(runtimeGone))
    }

    @Test
    fun `a status keeps the compare echo it was given`() {
        assertEquals("refs/heads/main", GatewayGitStatus(compare = "refs/heads/main").toChatGitStatus().compare)
        assertNull(GatewayGitStatus().toChatGitStatus().compare)
    }
}
