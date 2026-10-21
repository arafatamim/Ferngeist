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

    private fun session(
        cwd: String,
        updatedAt: Long,
    ) = SessionSummary(id = cwd, title = null, cwd = cwd, updatedAt = updatedAt, serverId = "server-1")
}
