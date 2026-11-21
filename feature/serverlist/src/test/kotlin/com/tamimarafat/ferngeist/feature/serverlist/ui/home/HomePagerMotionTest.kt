package com.tamimarafat.ferngeist.feature.serverlist.ui.home

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class HomePagerMotionTest {
    @Test
    fun settle_reboundsPastClampedPageInEitherDirection() =
        runTest {
            val pageWidth = 400f
            val motion = HomePagerMotion(PagerState { 2 }, MotionScheme.expressive().defaultSpatialSpec())

            for (destination in listOf(pageWidth, 0f)) {
                var scrollPosition = pageWidth - destination
                val distance = destination - scrollPosition
                val scope =
                    object : ScrollScope {
                        override fun scrollBy(pixels: Float): Float {
                            val previous = scrollPosition
                            scrollPosition = (previous + pixels).coerceIn(0f, pageWidth)
                            return scrollPosition - previous
                        }
                    }
                val renderedPositions = mutableListOf<Float>()

                withContext(TestFrameClock()) {
                    with(motion) {
                        scope.settle(distance, initialVelocity = 0f) {
                            renderedPositions += scrollPosition + overshootPx
                        }
                    }
                }

                assertTrue(renderedPositions.any { it > 0f && it < pageWidth })
                val overshot =
                    if (destination == 0f) renderedPositions.min() < -1f else renderedPositions.max() > pageWidth + 1f
                assertTrue("Expected visible rebound past page at $destination px", overshot)
                assertEquals(destination, scrollPosition + motion.overshootPx, 0.01f)
            }
        }
}

private class TestFrameClock : MonotonicFrameClock {
    private var frameTimeNanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        frameTimeNanos += 16_000_000L
        return onFrame(frameTimeNanos)
    }
}
