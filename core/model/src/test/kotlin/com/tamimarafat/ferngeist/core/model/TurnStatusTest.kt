package com.tamimarafat.ferngeist.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnStatusTest {
    @Test
    fun `no sessions and all-unknown sessions aggregate to unknown, never finished`() {
        assertEquals(TurnStatus.UNKNOWN, emptyList<TurnStatus>().aggregateTurnStatus())
        assertEquals(
            TurnStatus.UNKNOWN,
            listOf(TurnStatus.UNKNOWN, TurnStatus.UNKNOWN).aggregateTurnStatus(),
        )
    }

    @Test
    fun `needs attention outranks error, working, finished and unknown`() {
        assertEquals(
            TurnStatus.NEEDS_ATTENTION,
            listOf(
                TurnStatus.UNKNOWN,
                TurnStatus.FINISHED,
                TurnStatus.WORKING,
                TurnStatus.ERROR,
                TurnStatus.NEEDS_ATTENTION,
            ).aggregateTurnStatus(),
        )
    }

    @Test
    fun `error outranks working, which outranks finished, regardless of order`() {
        assertEquals(
            TurnStatus.ERROR,
            listOf(TurnStatus.WORKING, TurnStatus.ERROR).aggregateTurnStatus(),
        )
        assertEquals(
            TurnStatus.WORKING,
            listOf(TurnStatus.FINISHED, TurnStatus.WORKING, TurnStatus.UNKNOWN).aggregateTurnStatus(),
        )
        assertEquals(
            TurnStatus.FINISHED,
            listOf(TurnStatus.UNKNOWN, TurnStatus.FINISHED).aggregateTurnStatus(),
        )
    }
}
