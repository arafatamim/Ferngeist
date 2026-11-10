package com.tamimarafat.ferngeist.feature.chat.markdown

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableWidthsTest {
    @Test
    fun distribute_narrowTable_fillsAvailableWidth() {
        // Content fits, so the table stays one screen wide: the last column takes the slack.
        assertArrayEquals(
            intArrayOf(40, 160),
            distributeTableColumnWidths(preferredWidths = intArrayOf(40, 40), availableWidth = 200),
        )
    }

    @Test
    fun distribute_threeColumnTable_stillFitsByWrapping() {
        // Three columns are the most that may squeeze: each gets a third, so nothing scrolls.
        assertArrayEquals(
            intArrayOf(100, 100, 100),
            distributeTableColumnWidths(preferredWidths = intArrayOf(500, 500, 500), availableWidth = 300),
        )
    }

    @Test
    fun distribute_wideTable_keepsReadableColumnsAndOverflows() {
        // Four columns cannot keep a third of the width each: the table goes wider than the screen.
        val widths = distributeTableColumnWidths(preferredWidths = intArrayOf(200, 200, 200, 200), availableWidth = 300)
        assertArrayEquals(intArrayOf(100, 100, 100, 100), widths)
        assertTrue(widths.sum() > 300)
    }

    @Test
    fun distribute_wideTable_leavesShortColumnsAlone() {
        // Narrow columns keep their content width instead of being inflated to the squeeze cap.
        val widths =
            distributeTableColumnWidths(
                preferredWidths = intArrayOf(10, 400, 400, 400, 10),
                availableWidth = 300,
            )
        assertArrayEquals(intArrayOf(10, 100, 100, 100, 10), widths)
    }

    @Test
    fun distribute_cappedTable_spendsTheSlackOnTheWideColumn() {
        // Two narrow columns and one wide: capping frees enough to fill the width without scrolling.
        assertArrayEquals(
            intArrayOf(20, 20, 260),
            distributeTableColumnWidths(preferredWidths = intArrayOf(20, 20, 400), availableWidth = 300),
        )
    }

    @Test
    fun distribute_zeroAvailableWidth_returnsZeroWidths() {
        // A table measured while its container has no width must not divide by zero or grow.
        assertArrayEquals(
            intArrayOf(0, 0),
            distributeTableColumnWidths(preferredWidths = intArrayOf(40, 40), availableWidth = 0),
        )
    }
}
