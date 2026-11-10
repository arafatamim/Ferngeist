package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.git.graph.dayGroupSizes
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHistoryDaysTest {
    @Test
    fun `each day counts the rows under its own header`() {
        val days = listOf("Mon", "Mon", "Mon", "Sun", "Sun", "Sat")

        assertEquals(mapOf(0 to 3, 3 to 2, 5 to 1), dayGroupSizes(days))
    }

    @Test
    fun `a day that comes back later gets a header, and a count, of its own`() {
        // Rows are not grouped by day (a rebase left an older commit on top), so the walk gets
        // two "Mon" runs and each says how much it holds.
        val days = listOf("Mon", "Sun", "Mon")

        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 1), dayGroupSizes(days))
    }

    @Test
    fun `an empty history has no groups`() {
        assertEquals(emptyMap<Int, Int>(), dayGroupSizes(emptyList<String>()))
    }
}
