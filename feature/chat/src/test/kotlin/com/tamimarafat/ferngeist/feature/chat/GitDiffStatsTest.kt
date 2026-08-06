package com.tamimarafat.ferngeist.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class GitDiffStatsTest {
    @Test
    fun `GitDiffStats holds line counts`() {
        val stats = GitDiffStats(additions = 12, deletions = 3)

        assertEquals(12, stats.additions)
        assertEquals(3, stats.deletions)
    }
}
