package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class StackStartsTest {
    @Test
    fun `a grown mark keeps the same overlap with both neighbours`() {
        val widths = listOf(44, 53, 44)

        val starts = stackStarts(widths, overlap = 12)

        assertEquals(listOf(0, 32, 73), starts)
        // Each mark's drawn end overlaps the next mark's start by exactly the overlap.
        assertEquals(listOf(12, 12), (0 until widths.lastIndex).map { starts[it] + widths[it] - starts[it + 1] })
    }
}
