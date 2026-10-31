package com.tamimarafat.ferngeist.feature.chat.selection

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteDraftTest {
    @Test
    fun `quote preserves draft and formats each selected line`() {
        assertEquals("Draft\n\n> first\n> second\n\n", quoteDraft("Draft", "first\nsecond"))
    }

    @Test
    fun `quote starts an empty draft without a leading gap`() {
        assertEquals("> selected\n\n", quoteDraft("", "selected"))
    }
}
