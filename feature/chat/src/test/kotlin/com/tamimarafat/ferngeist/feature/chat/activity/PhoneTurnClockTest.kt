package com.tamimarafat.ferngeist.feature.chat.activity

import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneTurnClockTest {
    @Test
    fun sameSessionIdOnDifferentAgents_keepsSeparateActivity() {
        PhoneTurnClock.record("clock-a", "shared", 100, setOf("a"))
        PhoneTurnClock.record("clock-b", "shared", 200, setOf("b"))

        assertEquals(100L, PhoneTurnClock.lastTurn("clock-a", "shared"))
        assertEquals(200L, PhoneTurnClock.lastTurn("clock-b", "shared"))
        assertNull(PhoneTurnClock.lastTurn("clock-c", "shared"))
    }

    @Test
    fun completedPhoneTurnAfterLeaving_doesNotLookLikeExternalActivity() {
        PhoneTurnClock.record("clock-return", "chat", 100, setOf("prompt"))
        PhoneTurnClock.observe(
            "clock-return",
            "chat",
            listOf(
                ChatMessage(id = "prompt", role = ChatMessage.Role.USER),
                ChatMessage(role = ChatMessage.Role.ASSISTANT, turn = TurnTiming(100, 200)),
            ),
        )

        val lastTurn = PhoneTurnClock.lastTurn("clock-return", "chat")
        assertEquals(false, ActivityConfirmation(190).needsConfirmation(lastTurn, 210))
        assertEquals(true, ActivityConfirmation(205).needsConfirmation(lastTurn, 210))
    }

    @Test
    fun lateOlderEcho_cannotMoveThePhoneClockBackwards() {
        PhoneTurnClock.record("clock", "out-of-order", 200, setOf("new"))
        PhoneTurnClock.record("clock", "out-of-order", 100, setOf("old"))

        assertEquals(200L, PhoneTurnClock.lastTurn("clock", "out-of-order"))
    }
}
