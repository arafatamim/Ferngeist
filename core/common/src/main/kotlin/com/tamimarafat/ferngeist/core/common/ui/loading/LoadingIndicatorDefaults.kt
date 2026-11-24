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

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.min

/** Contains default values by the [LoadingIndicator]. */
@ExperimentalMaterial3ExpressiveApi
public object LoadingIndicatorDefaults {
    /** A [LoadingIndicator] default container width. */
    public val containerWidth: Dp = 48.dp

    /** A [LoadingIndicator] default container height. */
    public val containerHeight: Dp = 48.dp

    /** A [LoadingIndicator] default active indicator size. */
    public val indicatorSize: Dp = 38.dp

    /** A [LoadingIndicator] default container [Shape]. */
    public val containerShape: Shape
        @Composable get() = CircleShape

    /**
     * A [LoadingIndicator] default active indicator [Color] when using an uncontained
     * [LoadingIndicator].
     */
    public val indicatorColor: Color
        @Composable get() = MaterialTheme.colorScheme.primary

    /**
     * A [LoadingIndicator] default active indicator [Color] when using a
     * [ContainedLoadingIndicator].
     */
    public val containedIndicatorColor: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer

    /** A [LoadingIndicator] default container [Color] when using a [ContainedLoadingIndicator]. */
    public val containedContainerColor: Color
        @Composable get() = MaterialTheme.colorScheme.primaryContainer

    /**
     * The sequence of [RoundedPolygon]s that the indeterminate [LoadingIndicator] will morph
     * between when animating.
     *
     * By default, an indeterminate loading indicator will morph between seven shapes, but you may
     * provide your own shapes sequence when calling the API.
     */
    public val indeterminateIndicatorPolygons: List<RoundedPolygon> =
        listOf(
            MaterialShapes.SoftBurst,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Pentagon,
            MaterialShapes.Pill,
            MaterialShapes.Sunny,
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Oval,
        )

    /**
     * The sequence of [RoundedPolygon]s that the determinate [LoadingIndicator] will morph between
     * when animating. The loading indicator will morph between the shapes as its progress moves
     * between zero to one.
     *
     * By default, a determinate loading indicator will will morph between two shapes, but you may
     * provide your own shapes sequence when calling the API.
     */
    public val determinateIndicatorPolygons: List<RoundedPolygon> =
        listOf(
            // Rotating the circle gets us a smoother morphing to the soft-burst shapes, which is
            // also being rotated at the same angle.
            MaterialShapes.Circle.transformed(Matrix().apply { rotateZ(360f / 20) }),
            MaterialShapes.SoftBurst,
        )

    /**
     * A percentage value of the LoadingIndicator's active indicator scale when rendered within the
     * bounds of the container size.
     */
    internal val activeIndicatorScale =
        indicatorSize.value / min(containerWidth.value, containerHeight.value)
}
