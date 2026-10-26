package com.tamimarafat.ferngeist.core.common.ui.status

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentActivityTest {
    @Test
    fun `recent activity expires at the window boundary without a refresh`() =
        runTest {
            val values = mutableListOf<Boolean>()
            backgroundScope.launch {
                recentActivity(980_000) { 1_000_000 + testScheduler.currentTime }.collect { values.add(it) }
            }
            runCurrent()
            assertEquals(listOf(true), values)
            advanceTimeBy(39_999)
            runCurrent()
            assertEquals(listOf(true), values)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(true, false), values)
        }

    @Test
    fun `missing stale and future timestamps never claim recent activity`() =
        runTest {
            val values = mutableListOf<Boolean>()
            for (timestamp in listOf(null, 0, -1, 940_000, 1_000_001, Long.MAX_VALUE, Long.MIN_VALUE)) {
                recentActivity(timestamp) { 1_000_000 }.collect { values.add(it) }
            }
            assertEquals(List(7) { false }, values)
        }

    @Test
    fun `refresh advancing timestamp replaces the old expiry`() =
        runTest {
            val timestamps = MutableStateFlow<Long?>(980_000)
            val values = mutableListOf<Boolean>()
            backgroundScope.launch {
                timestamps
                    .flatMapLatest { recentActivity(it) { 1_000_000 + testScheduler.currentTime } }
                    .collect { values.add(it) }
            }
            runCurrent()
            assertEquals(listOf(true), values)
            advanceTimeBy(30_000)
            timestamps.value = 1_030_000
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(true, values.last())
            advanceTimeBy(50_000)
            runCurrent()
            assertEquals(false, values.last())
        }

    @Test
    fun `resuming after expiry does not revive the hint`() =
        runTest {
            val values = mutableListOf<Boolean>()
            val first =
                backgroundScope.launch {
                    recentActivity(980_000) { 1_000_000 + testScheduler.currentTime }.collect { values.add(it) }
                }
            runCurrent()
            assertEquals(listOf(true), values)
            first.cancel()
            advanceTimeBy(60_000)
            recentActivity(980_000) { 1_000_000 + testScheduler.currentTime }.collect { values.add(it) }
            assertEquals(listOf(true, false), values)
        }
}
