package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent.AgentMessage
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent.UserMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Perf harness, not a correctness test. Prints wall-clock cost of replaying a long
 * transcript so each optimization task can be measured before/after.
 *
 * Delete once the perf tasks land and the numbers are recorded in the ledger.
 */
class SessionLoadPerfTest {
    @Test
    fun replayTailCost() =
        runTest {
            val runtime = SessionRuntime(sessionId = "perf")
            runtime.beginHydration()
            repeat(5_000) { i -> runtime.onEvent(AgentMessage("chunk $i " + "y".repeat(80))) }
            val start = System.nanoTime()
            repeat(20_000) { i -> runtime.onEvent(AgentMessage("tail $i " + "z".repeat(80))) }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("PERF replayTailMs=$elapsedMs for 20000 chunks after 5000")
            runtime.completeHydration()
            // Consecutive agent chunks coalesce into one streaming assistant bubble.
            assertEquals(1, runtime.snapshot.value.messages.size)
        }

    @Test
    fun costGrowsWithHistoryLength() =
        runTest {
            val runtime = SessionRuntime(sessionId = "perf2")
            runtime.beginHydration()
            // Agent chunks coalesce into the trailing assistant bubble, so they never grow the
            // transcript on their own. User messages each append a distinct bubble, which is
            // how a real transcript gets long.
            repeat(400) { i -> runtime.onEvent(UserMessage("seed $i " + "q".repeat(100))) }
            val seeded = runtime.snapshot.value.messages.size
            val start = System.nanoTime()
            repeat(2_000) { i -> runtime.onEvent(AgentMessage("more $i " + "q".repeat(100))) }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("PERF tailAfter400Ms=$elapsedMs for 2000 chunks (seededMessages=$seeded)")
            runtime.completeHydration()
            assertEquals(401, runtime.snapshot.value.messages.size)
        }

    @Test
    fun costGrowsWithoutHistory() =
        runTest {
            val runtime = SessionRuntime(sessionId = "perf3")
            runtime.beginHydration()
            // Same tail as costGrowsWithHistoryLength, but with no prior messages. Comparing
            // this against costGrowsWithHistoryLength isolates the per-chunk cost of history
            // length from the fixed per-chunk cost.
            val start = System.nanoTime()
            repeat(2_000) { i -> runtime.onEvent(AgentMessage("more $i " + "q".repeat(100))) }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("PERF tailAfter0Ms=$elapsedMs for 2000 chunks")
            runtime.completeHydration()
            assertEquals(1, runtime.snapshot.value.messages.size)
        }
}
