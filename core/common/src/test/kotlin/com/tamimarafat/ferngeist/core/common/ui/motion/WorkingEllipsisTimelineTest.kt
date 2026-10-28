package com.tamimarafat.ferngeist.core.common.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingEllipsisTimelineTest {
    private val dots = 0..2

    @Test
    fun dotsEnter_leftToRight_withEarlierDotsStillShowing() {
        assertTrue(ellipsisDotFrame(0, 200f).alpha > 0f)
        assertEquals(0f, ellipsisDotFrame(1, 200f).alpha)
        assertTrue(ellipsisDotFrame(1, 400f).alpha > 0f)
        assertEquals(0f, ellipsisDotFrame(2, 400f).alpha)
        assertEquals(1f, ellipsisDotFrame(0, 600f).alpha)
    }

    @Test
    fun rest_holdsAllDotsLevelAndOpaque() {
        dots.forEach { assertEquals(DotFrame(alpha = 1f, lift = 0f), ellipsisDotFrame(it, ELLIPSIS_REST_MS)) }
    }

    @Test
    fun hop_peaksThenLandsBackOnTheBaseline() {
        val peak = ellipsisDotFrame(0, 160f).lift
        assertEquals(1f, peak, 0.001f)
        assertTrue(ellipsisDotFrame(0, 380f).lift in 0f..0.05f)
        assertEquals(0f, ellipsisDotFrame(0, 400f).lift)
    }

    @Test
    fun pause_hidesAllDots_thenTheLoopRepeats() {
        dots.forEach { assertEquals(0f, ellipsisDotFrame(it, 1400f).alpha) }
        dots.forEach { assertEquals(ellipsisDotFrame(it, 460f), ellipsisDotFrame(it, 460f + ELLIPSIS_PERIOD_MS)) }
    }
}
