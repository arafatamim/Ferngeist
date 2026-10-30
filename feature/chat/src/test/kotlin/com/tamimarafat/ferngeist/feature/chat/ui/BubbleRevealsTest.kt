package com.tamimarafat.ferngeist.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleRevealsTest {
    @Test
    fun `reopening a streaming bubble shows received prose and only reveals later text`() {
        val reveals = BubbleReveals(initialTargets = mapOf("opening" to 100, "closing" to 50))

        val opening = reveals.forRun("opening", startHidden = true, target = 100)
        val closing = reveals.forRun("closing", startHidden = true, target = 50)

        assertTrue(opening.isSettled)
        assertTrue(closing.isSettled)
        assertFalse(reveals.isRevealing)
        assertEquals(1f, opening.alphaAt(99), 0f)
        assertEquals(1f, closing.alphaAt(49), 0f)

        closing.target = 60
        assertFalse(closing.isSettled)
        assertEquals(1f, closing.alphaAt(49), 0f)
        assertEquals(0f, closing.alphaAt(50), 0f)
        closing.advance(frameMs = 1_000, dtSeconds = 1f)
        closing.advance(frameMs = 2_000, dtSeconds = 0f)
        assertTrue(closing.isSettled)
        assertEquals(1f, closing.alphaAt(59), 0f)

        val later = reveals.forRun("later", startHidden = true, target = 20)
        assertFalse(later.isSettled)
        assertEquals(0f, later.alphaAt(0), 0f)
    }
}
