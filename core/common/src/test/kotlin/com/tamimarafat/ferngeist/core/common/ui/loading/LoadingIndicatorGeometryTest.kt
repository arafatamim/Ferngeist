package com.tamimarafat.ferngeist.core.common.ui.loading

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import com.tamimarafat.ferngeist.core.common.ui.loading.geometry.normalizeCubics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class LoadingIndicatorGeometryTest {
    @Test
    fun morphBetweenBlobAndStar_keepsUnitFilledArea() {
        val morph = Morph(MaterialShapes.ClamShell.normalized(), MaterialShapes.VerySunny.normalized())
        for (step in 0..110) {
            val cubics = normalizeCubics(morph.asCubics(step / 100f))
            assertEquals("area at step $step", 1.0, sampledMass(cubics)[0], 0.002)
        }
    }

    @Test
    fun pentagon_pivotsOnItsPolygonCenterRatherThanItsCentroid() {
        val morph = Morph(MaterialShapes.Pentagon.normalized(), MaterialShapes.Pill.normalized())
        val cubics = normalizeCubics(morph.asCubics(0f))
        val points =
            cubics.flatMap { cubic ->
                (0..SAMPLES_PER_CUBIC).map {
                    curvePoint(
                        cubic,
                        it / SAMPLES_PER_CUBIC.toFloat(),
                    )
                }
            }
        val boundsCenterY = (points.maxOf { it.second } + points.minOf { it.second }) / 2
        assertEquals("rotation pivot is the bounds center", 0.0, boundsCenterY.toDouble(), 0.001)
        assertTrue("the centroid sits off the pivot", abs(sampledMass(cubics)[2]) > 0.02)
    }

    @Test
    fun normalizedMorphs_fitTheContainerThroughRotationAndSpringOvershoot() {
        val morphs = morphSequence(LoadingIndicatorDefaults.indeterminateIndicatorPolygons, circularSequence = true)
        val scale = calculateScaleFactor(morphs)
        for (morph in morphs) {
            for (step in 0..110) {
                for (cubic in normalizeCubics(morph.asCubics(step / 100f))) {
                    val radius =
                        maxOf(
                            hypot(cubic.anchor0X, cubic.anchor0Y),
                            hypot(cubic.control0X, cubic.control0Y),
                            hypot(cubic.control1X, cubic.control1Y),
                            hypot(cubic.anchor1X, cubic.anchor1Y),
                        )
                    assertTrue("rotating outline stays within the container", radius * scale <= 0.5f)
                }
            }
        }
    }

    @Test
    fun referenceSizedMorphs_stayInsideTheContainerWithPopHeadroom() {
        val morphs = morphSequence(LoadingIndicatorDefaults.indeterminateIndicatorPolygons, circularSequence = true)
        for ((index, morph) in morphs.withIndex()) {
            for (step in 0..110) {
                val progress = step / 100f
                val scale = LoadingIndicatorReference.level(index, progress, morphs.size)
                val radius = normalizeCubics(morph.asCubics(progress)).maxOf { curveRadius(it) }
                assertTrue(
                    "morph $index at $progress reaches ${radius * scale * POP_HEADROOM}",
                    radius * scale * POP_HEADROOM <= 0.5f,
                )
            }
        }
    }

    private fun curveRadius(cubic: Cubic): Float =
        (0..SAMPLES_PER_CUBIC).maxOf { step ->
            val (x, y) = curvePoint(cubic, step / SAMPLES_PER_CUBIC.toFloat())
            hypot(x, y)
        }

    private fun curvePoint(
        cubic: Cubic,
        t: Float,
    ): Pair<Float, Float> {
        val u = 1 - t
        return Pair(
            u * u * u * cubic.anchor0X + 3 * u * u * t * cubic.control0X + 3 * u * t * t * cubic.control1X +
                t * t * t * cubic.anchor1X,
            u * u * u * cubic.anchor0Y + 3 * u * u * t * cubic.control0Y + 3 * u * t * t * cubic.control1Y +
                t * t * t * cubic.anchor1Y,
        )
    }

    // Independent polygon integration checks the rendered curves, including asymmetric silhouettes.
    private fun sampledMass(cubics: List<Cubic>): DoubleArray {
        var area = 0.0
        var momentX = 0.0
        var momentY = 0.0
        var previousX = cubics.last().anchor1X.toDouble()
        var previousY = cubics.last().anchor1Y.toDouble()
        for (cubic in cubics) {
            for (step in 1..100) {
                val t = step / 100.0
                val u = 1 - t
                val x =
                    u * u * u * cubic.anchor0X + 3 * u * u * t * cubic.control0X +
                        3 * u * t * t * cubic.control1X + t * t * t * cubic.anchor1X
                val y =
                    u * u * u * cubic.anchor0Y + 3 * u * u * t * cubic.control0Y +
                        3 * u * t * t * cubic.control1Y + t * t * t * cubic.anchor1Y
                val cross = previousX * y - x * previousY
                area += cross
                momentX += (previousX + x) * cross
                momentY += (previousY + y) * cross
                previousX = x
                previousY = y
            }
        }
        return doubleArrayOf(abs(area / 2), momentX / (3 * area), momentY / (3 * area))
    }

    private companion object {
        const val SAMPLES_PER_CUBIC = 16

        // The largest fitted pop is about 10%; keep twice that as margin.
        const val POP_HEADROOM = 1.2f
    }
}
