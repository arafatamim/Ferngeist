package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class CwdSuggestionsTest {
    @Test
    fun worktreeChat_suggestsItsRepoInsteadOfTheWorktreePath() {
        val suggestions =
            buildCwdSuggestions("", emptyList(), listOf(session("/work/api/.worktrees/feat-x", updatedAt = 1)))

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
            )

        assertEquals(listOf("/work/api", "/work/app"), suggestions.map { it.cwd })
    }

    @Test
    fun worktreeOutsideTheRepo_foldsIntoItsRepoAndIsNotRemovable() {
        val rootOf = { cwd: String -> if (cwd.startsWith("/orca/api/")) "/work/api" else cwd }
        val suggestions =
            buildCwdSuggestions(
                "",
                recentCwds = listOf("/orca/api/feat-y", "/work/app"),
                sessions = listOf(session("/orca/api/feat-z", updatedAt = 1)),
                rootOf = rootOf,
            )

        assertEquals(
            listOf(
                CwdSuggestion("/work/api", CwdSuggestionSource.SESSION),
                CwdSuggestion("/work/app", CwdSuggestionSource.RECENT),
            ),
            suggestions,
        )
    }

    private fun session(
        cwd: String,
        updatedAt: Long,
    ) = SessionSummary(id = cwd, title = null, cwd = cwd, updatedAt = updatedAt, serverId = "server-1")
}
