package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.cwdKey
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorktreeScopeTest {
    private val sessions =
        listOf(
            session("main", "/r"),
            session("feat", "/r/.worktrees/feat"),
            session("feat-sub", "/r/.worktrees/feat/src"),
            session("feat-x", "/r/.worktrees/feat-x"),
            session("none", null),
        )

    @Test
    fun all_keepsEverySession() {
        assertEquals(sessions, filterSessionsByScope(sessions, "/r", WorktreeScope.All))
    }

    @Test
    fun main_keepsOnlyTheRepoCheckout() {
        assertEquals(listOf("main"), filterSessionsByScope(sessions, "/r/", WorktreeScope.Main).map { it.id })
    }

    @Test
    fun worktree_keepsItsDirectoryAndSubdirectoriesButNotASiblingWithTheSamePrefix() {
        val visible = filterSessionsByScope(sessions, "/r", WorktreeScope.Worktree("/r/.worktrees/feat"))

        assertEquals(listOf("feat", "feat-sub"), visible.map { it.id })
    }

    @Test
    fun scope_resetsToAllWhenTheRepoFilterMoves() {
        val picked = ScopeSelection("/r", WorktreeScope.Main)

        assertEquals(WorktreeScope.Main, effectiveScope(picked, "/R/"))
        assertEquals(WorktreeScope.All, effectiveScope(picked, "/other"))
        assertEquals(WorktreeScope.All, effectiveScope(picked, null))
        assertEquals(WorktreeScope.All, effectiveScope(null, "/r"))
    }

    @Test
    fun newChat_startsInTheWorktreeOrTheRepo() {
        assertEquals("/r", scopeCwd("/r", WorktreeScope.All))
        assertEquals("/r", scopeCwd("/r", WorktreeScope.Main))
        assertEquals("/r/.worktrees/a", scopeCwd("/r", WorktreeScope.Worktree("/r/.worktrees/a")))
    }

    @Test
    fun options_keepTheRepoWorktreesCountChatsAndNameDetachedOnesByFolder() {
        val options =
            worktreeScopeOptions(
                repo = "/r",
                worktrees =
                    listOf(
                        managed("1", "/r", "/r/.worktrees/feat", "feat"),
                        managed("2", "/other", "/other/.worktrees/z", "z"),
                        managed("3", "/r", "/r/.worktrees/hand", ""),
                    ),
                sessions = sessions,
            )

        assertEquals(listOf("feat", "hand"), options.map { it.branch })
        assertEquals(listOf(2, 0), options.map { it.chats })
        assertEquals("1", options[0].worktree.id)
    }

    private fun session(
        id: String,
        cwd: String?,
    ) = SessionSummary(id = id, title = null, cwd = cwd, updatedAt = null, serverId = "server-1")

    @Test
    fun sessionsInRepo_includesChatsInWorktreesOutsideTheRepo() {
        val all =
            listOf(
                session("repo", "/work/api"),
                session("managed", "/work/api/.worktrees/feat-x"),
                session("external", "/orca/api/feat-y"),
                session("deeper", "/orca/api/feat-y/src"),
                session("other", "/orca/app/feat-y"),
            )

        assertEquals(
            listOf("repo", "managed", "external", "deeper"),
            sessionsInRepo(all, "/work/api", listOf("/orca/api/feat-y")).map { it.id },
        )
        assertEquals(listOf("repo", "managed"), sessionsInRepo(all, "/work/api", emptyList()).map { it.id })
        assertEquals(all.map { it.id }, sessionsInRepo(all, null, listOf("/orca/api/feat-y")).map { it.id })
    }

    @Test
    fun knownProjects_foldWorktreesIntoTheirRepoNewestFirst() {
        val sessions =
            listOf(
                session("old", "/work/app").copy(updatedAt = 1),
                session("managed", "/work/api/.worktrees/feat-x").copy(updatedAt = 3),
                session("external", "/orca/api/feat-y/src").copy(updatedAt = 2),
                session("blank", " "),
            )

        assertEquals(
            listOf("/work/api", "/work/app", "/work/lib"),
            knownProjects(
                sessions,
                recentCwds = listOf("/work/lib", "/work/API/"),
                managed = listOf(managed("w", "/work/api", "/orca/api/feat-y", "feat-y")),
            ),
        )
    }

    @Test
    fun knownProjects_dropFoldersTheGatewayReportsGone() {
        val sessions =
            listOf(
                session("app", "/work/app").copy(updatedAt = 3),
                session("gone", "/work/gone").copy(updatedAt = 2),
                session("folded", "/work/api/.worktrees/feat-y").copy(updatedAt = 1),
            )

        assertEquals(
            listOf("/work/app", "/work/api", "/work/lib"),
            knownProjects(
                sessions,
                recentCwds = listOf("/work/deleted", "/work/lib"),
                managed = emptyList(),
                missing = setOf(cwdKey("/work/gone"), cwdKey("/work/deleted")),
            ),
        )
    }

    @Test
    fun worktreeOf_findsTheWorktreeAndItsRepo() {
        val managed = listOf(managed("w", "/work/api", "/orca/api/feat-y", "feat-y"))

        assertEquals(WorktreeLocation("/work/api", "/orca/api/feat-y"), worktreeOf("/orca/api/feat-y/src", managed))
        assertEquals(
            WorktreeLocation("/work/app", "/work/app/.worktrees/feat-x"),
            worktreeOf("/work/app/.worktrees/feat-x/src", emptyList()),
        )
        assertNull(worktreeOf("/elsewhere/feat-z", emptyList()))
        assertNull(worktreeOf("/work/api", managed))
    }

    private fun managed(
        id: String,
        repo: String,
        path: String,
        branch: String,
    ) = GatewayWorktree(
        id = id,
        repo = repo,
        path = path,
        branch = branch,
        baseCommit = "abc",
        createdAt = "2026-10-21T00:00:00Z",
    )
}
