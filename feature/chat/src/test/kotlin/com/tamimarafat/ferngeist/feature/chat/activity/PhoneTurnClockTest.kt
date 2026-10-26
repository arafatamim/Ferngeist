package com.tamimarafat.ferngeist.feature.chat.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneTurnClockTest {
    @Test
    fun sameSessionIdOnDifferentAgents_keepsSeparateActivity() {
        PhoneTurnClock.record("clock-a", "shared", 100)
        PhoneTurnClock.record("clock-b", "shared", 200)

        assertEquals(100L, PhoneTurnClock.lastTurn("clock-a", "shared"))
        assertEquals(200L, PhoneTurnClock.lastTurn("clock-b", "shared"))
        assertNull(PhoneTurnClock.lastTurn("clock-c", "shared"))
    }

    @Test
    fun lateOlderEcho_cannotMoveThePhoneClockBackwards() {
        PhoneTurnClock.record("clock", "out-of-order", 200)
        PhoneTurnClock.record("clock", "out-of-order", 100)

        assertEquals(200L, PhoneTurnClock.lastTurn("clock", "out-of-order"))
    }
}
