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
            distributeTableColumnWidths(
                preferredWidths = intArrayOf(40, 40),
                availableWidth = 200,
                minColumnWidth = READABLE_COLUMN,
            ),
        )
    }

    @Test
    fun distribute_threeWideColumns_overflowInsteadOfSqueezingBelowTheFloor() {
        // Three columns of prose are not a reason to squeeze: below the floor the table scrolls.
        val widths =
            distributeTableColumnWidths(
                preferredWidths = intArrayOf(500, 500, 500),
                availableWidth = 300,
                minColumnWidth = READABLE_COLUMN,
            )
        assertArrayEquals(intArrayOf(READABLE_COLUMN, READABLE_COLUMN, READABLE_COLUMN), widths)
        assertTrue(widths.sum() > 300)
    }

    @Test
    fun distribute_twoColumns_stillFitWhenTheShareClearsTheFloor() {
        // The floor is a minimum, not a target: a wide container still fits two columns exactly.
        assertArrayEquals(
            intArrayOf(200, 200),
            distributeTableColumnWidths(
                preferredWidths = intArrayOf(500, 500),
                availableWidth = 400,
                minColumnWidth = READABLE_COLUMN,
            ),
        )
    }

    @Test
    fun distribute_wideTable_keepsReadableColumnsAndOverflows() {
        // Four columns cannot hold the floor each: the table goes wider than the screen.
        val widths =
            distributeTableColumnWidths(
                preferredWidths = intArrayOf(200, 200, 200, 200),
                availableWidth = 300,
                minColumnWidth = READABLE_COLUMN,
            )
        assertArrayEquals(intArrayOf(READABLE_COLUMN, READABLE_COLUMN, READABLE_COLUMN, READABLE_COLUMN), widths)
        assertTrue(widths.sum() > 300)
    }

    @Test
    fun distribute_wideTable_leavesShortColumnsAlone() {
        // Narrow columns keep their content width instead of being inflated to the floor.
        val widths =
            distributeTableColumnWidths(
                preferredWidths = intArrayOf(10, 400, 400, 400, 10),
                availableWidth = 300,
                minColumnWidth = READABLE_COLUMN,
            )
        assertArrayEquals(
            intArrayOf(10, READABLE_COLUMN, READABLE_COLUMN, READABLE_COLUMN, 10),
            widths,
        )
    }

    @Test
    fun distribute_cappedTable_spendsTheSlackOnTheWideColumn() {
        // Two narrow columns and one wide: capping frees enough to fill the width without scrolling.
        assertArrayEquals(
            intArrayOf(20, 20, 260),
            distributeTableColumnWidths(
                preferredWidths = intArrayOf(20, 20, 400),
                availableWidth = 300,
                minColumnWidth = READABLE_COLUMN,
            ),
        )
    }

    @Test
    fun distribute_zeroAvailableWidth_returnsZeroWidths() {
        // A table measured while its container has no width must not divide by zero or grow.
        assertArrayEquals(
            intArrayOf(0, 0),
            distributeTableColumnWidths(
                preferredWidths = intArrayOf(40, 40),
                availableWidth = 0,
                minColumnWidth = READABLE_COLUMN,
            ),
        )
    }

    private companion object {
        /** A floor in pixels: these tests only care how the production floor is honoured. */
        const val READABLE_COLUMN = 120
    }
}
