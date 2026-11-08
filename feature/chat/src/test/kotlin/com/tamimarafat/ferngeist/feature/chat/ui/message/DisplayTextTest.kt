package com.tamimarafat.ferngeist.feature.chat.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTextTest {
    @Test
    fun cappedForDisplay_shortText_isUnchanged() {
        assertEquals("hello", "hello".cappedForDisplay())
    }

    @Test
    fun cappedForDisplay_hugeUnbreakableLine_isCutWithEllipsis() {
        val capped = "A".repeat(700_000).cappedForDisplay()

        assertTrue(capped.length < 20_000)
        assertTrue(capped.endsWith("…"))
    }
}
