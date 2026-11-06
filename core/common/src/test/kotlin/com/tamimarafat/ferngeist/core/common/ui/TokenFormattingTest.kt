package com.tamimarafat.ferngeist.core.common.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TokenFormattingTest {
    @Test
    fun `compact token formatting preserves counts beyond int range`() {
        assertEquals("5B", formatCompactTokens(5_000_000_000L, Locale.US))
    }
}
