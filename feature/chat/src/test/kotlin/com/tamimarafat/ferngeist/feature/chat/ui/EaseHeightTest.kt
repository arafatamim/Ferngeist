package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A panel's layout leads its animation (it stays tall while closing, is tall at once when opening);
 * what sits above it must glide with the animation, not hang or jump ahead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EaseHeightTest {
    @get:Rule
    val compose = createComposeRule()

    private var expanded by mutableStateOf(true)
    private var queueY = 0f
    private var tallPx = 0f
    private var restingPx = 0f

    @Test
    fun `a closing panel still laid out tall lets the queue above glide down instead of snapping`() {
        val path = pathAfter { expanded = false }

        assertEquals("it lands on the resting row", path.first() + gap, path.last(), 1f)
        assertTrue("no frame covers the gap at once", path.biggestStep() < gap / 2)
    }

    @Test
    fun `an opening panel laid out tall at once lets the queue above glide up instead of jumping`() {
        expanded = false
        val path = pathAfter { expanded = true }

        assertEquals("it lands above the open panel", path.first() - gap, path.last(), 1f)
        assertTrue("no frame covers the gap at once", path.biggestStep() < gap / 2)
    }

    private val gap get() = tallPx - restingPx

    private fun List<Float>.biggestStep() = zipWithNext { a, b -> kotlin.math.abs(b - a) }.max()

    /** Queue's y each frame after [change], from where it rested before. */
    private fun pathAfter(change: () -> Unit): List<Float> {
        compose.setContent {
            val density = LocalDensity.current
            tallPx = with(density) { 200.dp.toPx() }
            restingPx = with(density) { 56.dp.toPx() }
            val settled = remember { Animatable(if (expanded) tallPx else restingPx) }
            LaunchedEffect(expanded) { settled.animateTo(if (expanded) tallPx else restingPx, spring()) }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.BottomCenter)) {
                    Box(Modifier.size(40.dp).onGloballyPositioned { queueY = it.positionInRoot().y })
                    // The panel is tall for the whole test: its layout leads the animation.
                    Box(Modifier.easeHeight({ settled.value }, { 0f })) { Box(Modifier.height(200.dp)) }
                }
            }
        }
        compose.waitForIdle()
        val start = queueY
        compose.mainClock.autoAdvance = false
        change()
        return listOf(start) +
            List(60) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                queueY
            }
    }
}
