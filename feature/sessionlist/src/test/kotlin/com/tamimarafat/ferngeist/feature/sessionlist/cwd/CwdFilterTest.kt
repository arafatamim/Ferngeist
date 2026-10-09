package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class CwdFilterTest {
    private val sessions =
        listOf(
            session("a", "/work/api"),
            session("b", "/work/app"),
            session("c", "/work/api"),
            session("d", null),
        )

    @Test
    fun noFilter_keepsEverySession() {
        assertEquals(sessions, filterSessionsByCwd(sessions, null))
        assertEquals(sessions, filterSessionsByCwd(sessions, ""))
        assertEquals(sessions, filterSessionsByCwd(sessions, "   "))
    }

    @Test
    fun filter_keepsOnlySessionsInThatDirectory() {
        val visible = filterSessionsByCwd(sessions, "/work/api")

        assertEquals(listOf("a", "c"), visible.map { it.id })
    }

    @Test
    fun filter_matchesHandTypedVariantsOfTheGatewayPath() {
        val windows = listOf(session("w", "C:\\Projects\\Ferngeist"))

        assertEquals(
            listOf("w"),
            filterSessionsByCwd(windows, "c:\\projects\\ferngeist\\").map { it.id },
        )
        assertEquals(
            listOf("w"),
            filterSessionsByCwd(windows, " C:\\Projects\\Ferngeist ").map { it.id },
        )
    }

    @Test
    fun filter_hidesSessionsWithUnknownDirectory() {
        assertEquals(emptyList<String>(), filterSessionsByCwd(listOf(session("d", null)), "/work/api").map { it.id })
    }

    @Test
    fun filter_keepsWorktreeChatsUnderTheirRepo() {
        val tree =
            listOf(
                session("repo", "/work/api"),
                session("wt", "/work/api/.worktrees/feat-x"),
                session("other", "/work/app/.worktrees/feat-x"),
            )

        assertEquals(
            listOf("repo", "wt"),
            filterSessionsByCwd(tree, "/work/api").map { it.id },
        )
    }

    @Test
    fun filter_matchesAWorktreeRepoDespiteCase() {
        val tree = listOf(session("wt", "/Work/Api/.worktrees/feat-x"))

        assertEquals(listOf("wt"), filterSessionsByCwd(tree, "/work/api").map { it.id })
    }

    @Test
    fun worktreeRepoOf_readsOnlyTheFolderSegment() {
        assertEquals("/work/api", worktreeRepoOf("/work/api/.worktrees/feat-x"))
        // A repo that merely has "worktrees" in its name is not a managed worktree.
        assertEquals(null, worktreeRepoOf("/work/apix.worktrees/feat-x"))
        assertEquals(null, worktreeRepoOf("/work/api"))
        assertEquals(null, worktreeRepoOf(".worktrees/feat-x"))
        assertEquals("C:\\work\\api", worktreeRepoOf("C:\\work\\api\\.worktrees\\feat-x"))
    }

    @Test
    fun isSameCwd_ignoresSeparatorsCaseAndTrailingSlash() {
        assertEquals(true, isSameCwd("C:\\work\\api\\.worktrees\\feat-x", "c:/work/api/.worktrees/feat-x/"))
        assertEquals(false, isSameCwd("/work/api/.worktrees/feat-x", "/work/api"))
    }

    private fun session(
        id: String,
        cwd: String?,
    ) = SessionSummary(id = id, title = null, cwd = cwd, updatedAt = null, serverId = "server-1")
}
