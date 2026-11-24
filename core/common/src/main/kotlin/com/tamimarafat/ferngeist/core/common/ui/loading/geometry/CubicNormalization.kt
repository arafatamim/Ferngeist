package com.tamimarafat.ferngeist.core.common.ui.loading.geometry

import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.TransformResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Scales a morph of normalized polygons to unit filled area around their shared center (0.5, 0.5),
 * so concave morphs do not breathe. The spec video pivots there, not on the centroid: Pentagon
 * visibly swings around the container center.
 */
internal fun normalizeCubics(cubics: List<Cubic>): List<Cubic> {
    var twiceArea = 0.0
    for (cubic in cubics) {
        for (index in integrationPoints.indices) {
            val t = integrationPoints[index]
            val u = 1 - t
            val x =
                u * u * u * cubic.anchor0X + CUBIC_DEGREE * u * u * t * cubic.control0X +
                    CUBIC_DEGREE * u * t * t * cubic.control1X + t * t * t * cubic.anchor1X
            val y =
                u * u * u * cubic.anchor0Y + CUBIC_DEGREE * u * u * t * cubic.control0Y +
                    CUBIC_DEGREE * u * t * t * cubic.control1Y + t * t * t * cubic.anchor1Y
            val dx =
                CUBIC_DEGREE * (
                    u * u * (cubic.control0X - cubic.anchor0X) +
                        2 * u * t * (cubic.control1X - cubic.control0X) + t * t * (cubic.anchor1X - cubic.control1X)
                )
            val dy =
                CUBIC_DEGREE * (
                    u * u * (cubic.control0Y - cubic.anchor0Y) +
                        2 * u * t * (cubic.control1Y - cubic.control0Y) + t * t * (cubic.anchor1Y - cubic.control1Y)
                )
            twiceArea += (x * dy - y * dx) * integrationWeights[index]
        }
    }
    // A collapsed outline has no filled area to normalize; avoid an unbounded scale.
    if (abs(twiceArea) < MINIMUM_AREA) return cubics
    val scale = sqrt(2 / abs(twiceArea))
    return cubics.map { cubic ->
        cubic.transformed { x, y ->
            TransformResult(((x - CENTER) * scale).toFloat(), ((y - CENTER) * scale).toFloat())
        }
    }
}

// Five-point Gauss–Legendre integration is exact for this cubic area integrand.
private val integrationPoints =
    doubleArrayOf(0.046910077030668, 0.230765344947158, 0.5, 0.769234655052842, 0.953089922969332)
private val integrationWeights =
    doubleArrayOf(0.118463442528095, 0.239314335249683, 0.284444444444444, 0.239314335249683, 0.118463442528095)
private const val CUBIC_DEGREE = 3.0
private const val CENTER = 0.5
private const val MINIMUM_AREA = 0.000001
