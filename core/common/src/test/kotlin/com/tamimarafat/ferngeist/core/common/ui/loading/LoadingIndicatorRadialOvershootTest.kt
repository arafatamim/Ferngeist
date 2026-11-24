package com.tamimarafat.ferngeist.core.common.ui.loading

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.MotionDurationScale
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class LoadingIndicatorRadialOvershootTest {
    @Test
    fun earlyMorphImpulse_overshootsRecoilsAndSettlesContinuously() =
        runTest {
            for (durationScale in listOf(1f, 2f)) {
                val frames = motionSamples(durationScale)
                val impulseStart = frames.indexOfFirst { it.progress >= 0.1f }
                assertTrue("the morph gets under way", impulseStart >= 0)
                assertTrue(
                    "radial motion waits for the morph to get under way",
                    frames.take(impulseStart).all { it.scale == 1f },
                )
                assertTrue(
                    "expansion starts while the silhouette is still morphing",
                    frames.takeWhile { it.progress < 1f }.any { it.scale > 1.005f },
                )
                assertTrue("the impulse produces an outward overshoot", frames.any { it.scale > 1.025f })
                assertTrue("the spring recoils through its resting radius", frames.any { it.scale < 0.999f })
                assertTrue("radial excursion stays within the tuning range", frames.all { it.scale in 0.97f..1.15f })
                assertTrue(
                    "morph completion never snaps the radial spring back to rest",
                    frames.zipWithNext().all { (before, after) -> abs(after.scale - before.scale) < 0.06f },
                )
            }
        }

    @Test
    fun outlineRotation_settlesWithoutBackwardSteps() =
        runTest {
            for (durationScale in listOf(1f, 2f)) {
                for ((before, after) in motionSamples(durationScale).zipWithNext()) {
                    val delta = after.rotationDegrees - before.rotationDegrees
                    val step = ((delta % 360f) + 540f) % 360f - 180f
                    assertTrue("outline completion jerked backward by ${-step} degrees", step >= -0.01f)
                }
            }
        }

    @Test
    fun disabledSystemMotion_keepsTheOutlineAtRest() =
        runTest {
            assertTrue(motionSamples(0f).all { it.scale == 1f })
        }

    private suspend fun TestScope.motionSamples(durationScale: Float): List<MotionFrame> {
        val clock = BroadcastFrameClock()
        val motionScale =
            object : MotionDurationScale {
                override val scaleFactor = durationScale
            }
        val recomposer = Recomposer(coroutineContext + clock + motionScale)
        val runner = launch(clock + motionScale) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(EmptyApplier(), recomposer)
        val polygons = listOf(RoundedPolygon(3), RoundedPolygon(4))
        var visibleFrame = MotionFrame(0f, 1f, 0f)
        composition.setContent {
            val animation = rememberLoadingIndicatorAnimation(polygons, polygons.size)
            val progress = animation.morphProgress.value
            val scale = animation.radialScale.value
            val rotation = animation.rotationDegrees
            SideEffect { visibleFrame = MotionFrame(progress, scale, rotation) }
        }
        val samples = mutableListOf<MotionFrame>()
        try {
            repeat(250) {
                advanceTimeBy(16)
                runCurrent()
                clock.sendFrame(currentTime * 1_000_000)
                runCurrent()
                samples += visibleFrame
            }
            return samples
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
        }
    }

    private data class MotionFrame(
        val progress: Float,
        val scale: Float,
        val rotationDegrees: Float,
    )

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun onClear() = Unit

        override fun insertTopDown(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun insertBottomUp(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun remove(
            index: Int,
            count: Int,
        ) = Unit

        override fun move(
            from: Int,
            to: Int,
            count: Int,
        ) = Unit
    }
}
