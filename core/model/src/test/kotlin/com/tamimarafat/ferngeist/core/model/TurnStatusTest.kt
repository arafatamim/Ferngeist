package com.tamimarafat.ferngeist.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class TurnStatusTest {
    @Test
    fun `working aggregation uses the earliest active start and ignores completed turns`() {
        val clock = TestTimeSource()
        val oldFinish = clock.markNow()
        clock += 10.seconds
        val earliest = clock.markNow()
        clock += 20.seconds
        val recent = clock.markNow()
        val activity =
            listOf(
                TurnActivity(TurnStatus.FINISHED, oldFinish),
                TurnActivity(TurnStatus.WORKING, recent),
                TurnActivity(TurnStatus.WORKING, earliest),
            ).aggregateTurnActivity()

        clock += 5.seconds
        assertEquals(TurnStatus.WORKING, activity.status)
        assertEquals(25.seconds, activity.since?.elapsedNow())
    }

    @Test
    fun `finished aggregation uses the most recent completion`() {
        val clock = TestTimeSource()
        val old = clock.markNow()
        clock += 40.seconds
        val recent = clock.markNow()
        val activity =
            listOf(
                TurnActivity(TurnStatus.FINISHED, old),
                TurnActivity(TurnStatus.UNKNOWN),
                TurnActivity(TurnStatus.FINISHED, recent),
            ).aggregateTurnActivity()

        clock += 2.seconds
        assertEquals(TurnStatus.FINISHED, activity.status)
        assertEquals(2.seconds, activity.since?.elapsedNow())
    }

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
