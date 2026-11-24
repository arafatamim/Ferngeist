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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.fastCoerceIn
import androidx.graphics.shapes.RoundedPolygon

/**
 * A determinate loading indicator implementation.
 *
 * @param progress the progress of this loading indicator, where 0.0 represents no progress and 1.0
 *   represents full progress. Values outside of this range are coerced into the range. The
 *   indicator will morph its shapes between the provided [indicatorPolygons] according to the value
 *   of the progress.
 * @param modifier the [Modifier] to be applied to this loading indicator
 * @param containerColor the loading indicator's container color
 * @param indicatorColor the loading indicator's color
 * @param containerShape the loading indicator's container shape
 * @param indicatorPolygons a list of [RoundedPolygon]s for the sequence of shapes this loading
 *   indicator will morph between as it progresses from 0.0 to 1.0. The loading indicator expects at
 *   least two items in that list.
 * @throws IllegalArgumentException if the [indicatorPolygons] list holds less than two items
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoadingIndicatorImpl(
    progress: () -> Float,
    modifier: Modifier,
    containerColor: Color,
    indicatorColor: Color,
    containerShape: Shape,
    indicatorPolygons: List<RoundedPolygon>,
) {
    require(indicatorPolygons.size > 1) {
        "indicatorPolygons should have, at least, two RoundedPolygons"
    }
    val coercedProgress = { progress().fastCoerceIn(0f, 1f) }
    val path = remember { Path() }
    val scaleMatrix = remember { Matrix() }
    val morphSequence =
        remember(indicatorPolygons) {
            morphSequence(polygons = indicatorPolygons, circularSequence = false)
        }
    val morphScaleFactor =
        remember(morphSequence) {
            // Calculate the shapes scale factor that will be applied to the morphed path as it's
            // scaled into the available size.
            // This overall scale factor ensures that the shapes are rendered without clipping and
            // at the correct ratio within the component by taking into account their occupied size
            // as they rotate, and taking into account the spec's activeIndicatorScale.
            calculateScaleFactor(morphSequence) * LoadingIndicatorDefaults.activeIndicatorScale
        }
    Box(
        modifier =
            modifier
                .semantics(mergeDescendants = true) {
                    // Check for NaN, as the ProgressBarRangeInfo will throw an exception.
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            coercedProgress().takeUnless { it.isNaN() } ?: 0f,
                            0f..1f,
                        )
                }.size(
                    width = LoadingIndicatorDefaults.containerWidth,
                    height = LoadingIndicatorDefaults.containerHeight,
                ).fillMaxSize()
                .clip(containerShape)
                .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        // Using a Spacer to render the indicator's shapes. Maintains a square aspect ratio (1:1)
        // to prevent shape distortion during rendering.
        Spacer(
            modifier =
                Modifier
                    .aspectRatio(ratio = 1f, matchHeightConstraintsFirst = true)
                    .drawWithContent {
                        val progressValue = coercedProgress()
                        // Adjust the active morph index according to the progress.
                        val activeMorphIndex =
                            (morphSequence.size * progressValue)
                                .toInt()
                                .coerceAtMost(morphSequence.size - 1)
                        // Prepare the progress value that will be used for the active Morph.
                        val adjustedProgressValue =
                            if (progressValue == 1f && activeMorphIndex == morphSequence.size - 1) {
                                // Prevents a zero when the progress is one and we are at the last
                                // shape morph.
                                1f
                            } else {
                                (progressValue * morphSequence.size) % 1f
                            }

                        // Rotate counterclockwise.
                        val rotation = -progressValue * 180
                        rotate(rotation) {
                            drawPath(
                                path =
                                    processPath(
                                        path =
                                            morphSequence[activeMorphIndex].toPath(
                                                // Use the adjusted progress.
                                                progress = adjustedProgressValue,
                                                path = path,
                                            ),
                                        size = size,
                                        scaleFactor = morphScaleFactor,
                                        scaleMatrix = scaleMatrix,
                                    ),
                                color = indicatorColor,
                                style = Fill,
                            )
                        }
                    },
        )
    }
}

/**
 * An indeterminate loading indicator implementation.
 *
 * @param modifier the [Modifier] to be applied to this loading indicator
 * @param containerColor the loading indicator's container color
 * @param indicatorColor the loading indicator's color
 * @param containerShape the loading indicator's container shape
 * @param indicatorPolygons a list of [RoundedPolygon]s for the sequence of shapes this loading
 *   indicator will morph between. The loading indicator expects at least two items in that list.
 * @throws IllegalArgumentException if the [indicatorPolygons] list holds less than two items
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoadingIndicatorImpl(
    modifier: Modifier,
    containerColor: Color,
    indicatorColor: Color,
    containerShape: Shape,
    indicatorPolygons: List<RoundedPolygon>,
) {
    require(indicatorPolygons.size > 1) {
        "indicatorPolygons should have, at least, two RoundedPolygons"
    }
    val morphSequence =
        remember(indicatorPolygons) {
            morphSequence(polygons = indicatorPolygons, circularSequence = true)
        }
    val animation = rememberLoadingIndicatorAnimation(indicatorPolygons, morphSequence.size)

    val path = remember { Path() }
    val scaleMatrix = remember { Matrix() }
    Box(
        modifier =
            modifier
                .progressSemantics()
                .size(
                    width = LoadingIndicatorDefaults.containerWidth,
                    height = LoadingIndicatorDefaults.containerHeight,
                ).fillMaxSize()
                .clip(containerShape)
                .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier =
                Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true).drawWithContent {
                    val progress = animation.morphProgress.value
                    // Rotate clockwise.
                    rotate(animation.rotationDegrees) {
                        drawPath(
                            path =
                                processPath(
                                    path =
                                        morphSequence[animation.currentMorphIndex].toPath(
                                            // Preserve the spring overshoot in the outline;
                                            // area normalization keeps it balanced.
                                            progress = progress,
                                            path = path,
                                        ),
                                    size = size,
                                    // Per-shape size and the landing pop, both around the polygon center.
                                    scaleFactor =
                                        LoadingIndicatorReference.level(
                                            animation.currentMorphIndex,
                                            progress,
                                            morphSequence.size,
                                        ) * animation.radialScale.value,
                                    scaleMatrix = scaleMatrix,
                                ),
                            color = indicatorColor,
                            style = Fill,
                        )
                    }
                },
        )
    }
}
