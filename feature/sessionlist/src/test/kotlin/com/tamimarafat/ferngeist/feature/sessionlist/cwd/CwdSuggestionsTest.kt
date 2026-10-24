package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import org.junit.Assert.assertEquals
import org.junit.Test

class CwdSuggestionsTest {
    @Test
    fun worktreeChat_suggestsItsRepoInsteadOfTheWorktreePath() {
        val suggestions =
            buildCwdSuggestions(
                "",
                emptyList(),
                listOf(session("/work/api/.worktrees/feat-x", updatedAt = 1)),
                rootOf = { projectRoot(it, emptyList()) },
            )

        assertEquals(listOf("/work/api"), suggestions.map { it.cwd })
    }

    @Test
    fun worktreeChat_countsTowardItsRepoRecency() {
        val suggestions =
            buildCwdSuggestions(
                "",
                emptyList(),
                listOf(
                    session("/work/api", updatedAt = 1),
                    session("/work/app", updatedAt = 2),
                    session("/work/api/.worktrees/feat-x", updatedAt = 3),
                ),
                rootOf = { projectRoot(it, emptyList()) },
            )

        assertEquals(listOf("/work/api", "/work/app"), suggestions.map { it.cwd })
    }

    @Test
    fun worktreeOutsideTheRepo_foldsIntoItsRepoAndIsNotRemovable() {
        // A worktree git knows about, but outside the repo's own .worktrees folder.
        val managed = listOf(managedWorktree(repo = "/work/api", path = "/orca/api/feat-y"))
        val suggestions =
            buildCwdSuggestions(
                "",
                recentCwds = listOf("/orca/api/feat-y", "/work/app"),
                sessions = listOf(session("/orca/api/feat-y/src", updatedAt = 1)),
                rootOf = { projectRoot(it, managed) },
            )

        assertEquals(
            listOf(
                CwdSuggestion("/work/api", CwdSuggestionSource.SESSION),
                CwdSuggestion("/work/app", CwdSuggestionSource.RECENT),
            ),
            suggestions,
        )
    }

    private fun managedWorktree(
        repo: String,
        path: String,
    ) = GatewayWorktree(
        id = path,
        repo = repo,
        path = path,
        branch = path.substringAfterLast('/'),
        baseCommit = "abc",
        createdAt = "2026-10-21T00:00:00Z",
    )

    private fun session(
        cwd: String,
        updatedAt: Long,
    ) = SessionSummary(id = cwd, title = null, cwd = cwd, updatedAt = updatedAt, serverId = "server-1")
}
