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
    fun chatsInWorktree_takesEveryChatInThatDirectoryOrBelow() {
        val tree =
            listOf(
                session("a", "/work/api/.worktrees/feat-x"),
                session("b", "/Work/api/.worktrees/feat-x/"),
                session("sub", "/work/api/.worktrees/feat-x/src"),
                session("repo", "/work/api"),
                session("other", "/work/api/.worktrees/feat-y"),
            )

        assertEquals(listOf("a", "b", "sub"), chatsInWorktree(tree, "a", "/work/api/.worktrees/feat-x"))
        // The worktree dropped off the listing: only the chat the user acted on is known to be in it.
        assertEquals(listOf("a"), chatsInWorktree(tree, "a", null))
    }

    private fun session(
        id: String,
        cwd: String?,
    ) = SessionSummary(id = id, title = null, cwd = cwd, updatedAt = null, serverId = "server-1")
}
