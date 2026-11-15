package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ui.transcript.TranscriptRows
import com.tamimarafat.ferngeist.feature.chat.ui.transcript.toTurns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class StreamFollowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `without active follow live growth leaves the anchor still`() {
        val fixture = mount(follow = false)
        assertEquals(List(8) { 0 }, grow(fixture, 200))
    }

    @Test
    fun `large live growth follows through intermediate offsets instead of snapping`() {
        val fixture = mount()
        val offsets = grow(fixture, 200)
        assertSmooth(offsets, 200)
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals(200, fixture.list.firstVisibleItemScrollOffset - fixture.startOffset)
    }

    @Test
    fun `last rail ending keeps the viewport where following stopped`() {
        val fixture = mount()
        grow(fixture, 200)
        val stoppedIndex = fixture.list.firstVisibleItemIndex
        val stoppedOffset = fixture.list.firstVisibleItemScrollOffset
        assertTrue(stoppedOffset > fixture.startOffset)
        compose.runOnIdle { fixture.scroll.activeRails.intValue = 0 }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals(stoppedIndex, fixture.list.firstVisibleItemIndex)
        assertEquals(stoppedOffset, fixture.list.firstVisibleItemScrollOffset)
        assertEquals(List(8) { 0 }, grow(fixture, 200))
    }

    @Test
    fun `small live growth also eases instead of stepping by a whole line`() {
        val fixture = mount()
        repeat(4) { assertSmooth(grow(fixture, 24), 24) }
    }

    @Test
    fun `jump pinned streaming reply follows smoothly without a live tool rail`() {
        val fixture = mount(pinned = true)
        assertSmooth(grow(fixture, 200), 200)
    }

    @Test
    fun `pinned reply reaches its final end when streaming ends before the ease arrives`() {
        val fixture = mount(pinned = true)
        val offsets = grow(fixture, 200)
        assertTrue("Completion must happen before convergence: $offsets", offsets.last() in 1..199)
        compose.runOnIdle { fixture.scroll.live = false }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals(200, fixture.list.firstVisibleItemScrollOffset - fixture.startOffset)
    }

    @Test
    fun `reader release prevents completion from resuming a pending pinned ease`() {
        val fixture = mount(pinned = true)
        grow(fixture, 200)
        val releasedOffset = fixture.list.firstVisibleItemScrollOffset
        compose.runOnIdle {
            fixture.scroll.userScrollDetector.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
            fixture.scroll.live = false
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals(releasedOffset, fixture.list.firstVisibleItemScrollOffset)
        assertEquals(List(8) { 0 }, grow(fixture, 200))
    }

    @Test
    fun `reader release leaves later live growth below the viewport`() {
        val fixture = mount()
        grow(fixture, 200)
        compose.runOnIdle {
            fixture.scroll.userScrollDetector.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        }
        val offsets = grow(fixture, 200)
        assertEquals(List(8) { 0 }, offsets)
    }

    private fun mount(
        follow: Boolean = true,
        pinned: Boolean = false,
    ): Fixture {
        val list = LazyListState()
        val scroll = ChatScrollHandle(list)
        val height = mutableIntStateOf(1000)
        val prompt = ChatMessage(id = "prompt", role = ChatMessage.Role.USER)
        scroll.live = true
        scroll.activeRails.intValue = if (follow && !pinned) 1 else 0
        scroll.rows = TranscriptRows(resumedSession = false, hasOlder = false, turns = listOf(prompt).toTurns())
        scroll.onBottomPadding(80)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            scroll.relatchPx = with(LocalDensity.current) { 48.dp.toPx() }
            LaunchedEffect(scroll) {
                if (pinned) scroll.jumpToBottom()
                scroll.follow()
            }
            LaunchedEffect(scroll) { scroll.relatch() }
            LaunchedEffect(scroll) { scroll.watchEnd() }
            LazyColumn(
                state = list,
                modifier = Modifier.size(300.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = prompt.id) { Box(Modifier.height(height.intValue.dp)) }
                item(key = BOTTOM_SPACER_KEY) { Box(Modifier.height(80.dp)) }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        return Fixture(list, scroll, height, list.firstVisibleItemScrollOffset)
    }

    private fun grow(
        fixture: Fixture,
        amount: Int,
    ): List<Int> {
        val start = fixture.list.firstVisibleItemScrollOffset
        compose.runOnIdle { fixture.height.intValue += amount }
        return (1..8).map {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            fixture.list.firstVisibleItemScrollOffset - start
        }
    }

    private fun assertSmooth(
        offsets: List<Int>,
        growth: Int,
    ) {
        assertTrue("Growth should traverse intermediate positions: $offsets", offsets.distinct().size > 2)
        val steps = (listOf(0) + offsets).zipWithNext { before, after -> after - before }
        assertTrue("No frame should jump by a whole growth step: $steps", steps.all { it in 0..growth / 2 })
    }

    private data class Fixture(
        val list: LazyListState,
        val scroll: ChatScrollHandle,
        val height: MutableIntState,
        val startOffset: Int,
    )
}
