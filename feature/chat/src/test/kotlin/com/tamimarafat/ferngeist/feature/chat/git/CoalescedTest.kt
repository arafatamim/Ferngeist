package com.tamimarafat.ferngeist.feature.chat.git

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescedTest {
    @Test
    fun run_inBurstWhileInFlight_reRunsOnceAfter() =
        runTest {
            var calls = 0
            val gate = CompletableDeferred<Unit>()
            val coalesced =
                Coalesced(this) {
                    calls++
                    if (calls == 1) gate.await()
                }

            coalesced.run()
            runCurrent()
            repeat(3) { coalesced.run() }
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, calls)
        }

    @Test
    fun run_afterIdle_startsAFreshFetch() =
        runTest {
            var calls = 0
            val coalesced = Coalesced(this) { calls++ }

            coalesced.run()
            advanceUntilIdle()
            coalesced.run()
            advanceUntilIdle()

            assertEquals(2, calls)
        }
}
