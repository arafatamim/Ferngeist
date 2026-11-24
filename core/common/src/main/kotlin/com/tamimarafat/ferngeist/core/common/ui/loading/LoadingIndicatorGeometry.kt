/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Vendored from AndroidX Material 3 1.5.0. See README.md for provenance and local changes.

package com.tamimarafat.ferngeist.core.common.ui.loading

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.util.fastForEach
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import com.tamimarafat.ferngeist.core.common.ui.loading.geometry.normalizeCubics
import kotlin.math.hypot

/**
 * Returns a list of [Morph]s that describe the sequence of shapes that the [LoadingIndicator]
 * should animate in a loop.
 *
 * This function will create a morph between each consecutive [RoundedPolygon] shaped in the
 * provided array, and will create a final morph from the last shape to the first shape.
 *
 * Note that each [RoundedPolygon] within the returns [Morph]s is normalized here.
 *
 * @param polygons a [List] of [RoundedPolygon]s to create [Morph]s for
 * @param circularSequence indicate if an additional [Morph] should be created from the last item to
 *   the first item of the list in order to create a circular sequence of morphs
 * @see RoundedPolygon.normalized
 */
internal fun morphSequence(
    polygons: List<RoundedPolygon>,
    circularSequence: Boolean,
): List<Morph> =
    buildList {
        for (i in polygons.indices) {
            if (i + 1 < polygons.size) {
                add(Morph(polygons[i].normalized(), polygons[i + 1].normalized()))
            } else if (circularSequence) {
                // Create a morph from the last shape to the first shape
                add(Morph(polygons[i].normalized(), polygons[0].normalized()))
            }
        }
    }

/**
 * Reserves the largest rotation radius of the area-normalized morphs, including spring overshoot.
 * Simple axis-aligned bounds would clip star tips as they rotate.
 * shortcut: samples the morph envelope, with padding for extrema between samples.
 */
internal fun calculateScaleFactor(morphs: List<Morph>): Float {
    val radius =
        morphs.maxOf { morph ->
            (0..ENVELOPE_STEPS).maxOf { step ->
                val progress = step * ENVELOPE_MAX_PROGRESS / ENVELOPE_STEPS
                normalizeCubics(morph.asCubics(progress)).maxOf { cubic ->
                    maxOf(
                        hypot(cubic.anchor0X, cubic.anchor0Y),
                        hypot(cubic.control0X, cubic.control0Y),
                        hypot(cubic.control1X, cubic.control1Y),
                        hypot(cubic.anchor1X, cubic.anchor1Y),
                    )
                }
            }
        }
    return 1f / (2f * radius * ENVELOPE_PADDING)
}

/**
 * Process a given path to scale it and center it inside the given size.
 *
 * @param path a [Path] with unit filled area around the polygon center
 * @param size a [Size] that the provided [path] is going to be scaled and centered into
 * @param scaleFactor a scale factor that will be taken into account uniformly when the [path] is
 *   scaled (i.e. the scaleX would be the [size] width x the scale factor, and the scaleY would be
 *   the [size] height x the scale factor)
 * @param scaleMatrix a [Matrix] that would be used to apply the scaling. Note that any provided
 *   matrix will be reset in this function.
 */
internal fun processPath(
    path: Path,
    size: Size,
    scaleFactor: Float,
    scaleMatrix: Matrix = Matrix(),
): Path {
    scaleMatrix.reset()

    scaleMatrix.scale(x = size.width * scaleFactor, y = size.height * scaleFactor)

    // Scale to the desired size.
    path.transform(scaleMatrix)

    // Place the polygon center at the container center, where the outline rotates.
    path.translate(size.center)
    return path
}

/** Transforms a polygon using Compose's matrix without depending on Material internals. */
internal fun RoundedPolygon.transformed(matrix: Matrix): RoundedPolygon =
    transformed { x, y ->
        val point = matrix.map(Offset(x, y))
        TransformResult(point.x, point.y)
    }

/** Only the closed, unrotated path used by loading indicators is needed here. */
internal fun Morph.toPath(
    progress: Float,
    path: Path,
): Path {
    path.rewind()
    val cubics = normalizeCubics(asCubics(progress))
    cubics.firstOrNull()?.let { path.moveTo(it.anchor0X, it.anchor0Y) }
    cubics.fastForEach {
        path.cubicTo(it.control0X, it.control0Y, it.control1X, it.control1Y, it.anchor1X, it.anchor1Y)
    }
    path.close()
    return path
}

private const val ENVELOPE_STEPS = 24
private const val ENVELOPE_MAX_PROGRESS = 1.1f
private const val ENVELOPE_PADDING = 1.05f
