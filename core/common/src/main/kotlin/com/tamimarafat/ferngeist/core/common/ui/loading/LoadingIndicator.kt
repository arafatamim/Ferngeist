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

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.graphics.shapes.RoundedPolygon

// TODO Update the docs images to point to the loading indicator

/**
 * A Material Design loading indicator.
 *
 * This version of the loading indicator morphs between its [polygons] shapes by the value of its
 * [progress].
 *
 * ![Loading indicator
 * image](https://developer.android.com/images/reference/androidx/compose/material3/loading-indicator.png)
 *
 * It can be created like this:
 *
 * @sample androidx.compose.material3.samples.DeterminateLoadingIndicatorSample
 * @param progress the progress of this loading indicator, where 0.0 represents no progress and 1.0
 *   represents full progress. Values outside of this range are coerced into the range. The
 *   indicator will morph its shapes between the provided [polygons] according to the value of the
 *   progress.
 * @param modifier the [Modifier] to be applied to this loading indicator
 * @param color the loading indicator's color
 * @param polygons a list of [RoundedPolygon]s for the sequence of shapes this loading indicator
 *   will morph between as it progresses from 0.0 to 1.0. The loading indicator expects at least two
 *   items in that list.
 * @throws IllegalArgumentException if the [polygons] list holds less than two items
 *
 * @material3expressive
 */
@ExperimentalMaterial3ExpressiveApi
@Composable
public fun LoadingIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = LoadingIndicatorDefaults.indicatorColor,
    polygons: List<RoundedPolygon> = LoadingIndicatorDefaults.determinateIndicatorPolygons,
): Unit =
    LoadingIndicatorImpl(
        progress = progress,
        modifier = modifier,
        containerColor = Color.Unspecified,
        indicatorColor = color,
        containerShape = LoadingIndicatorDefaults.containerShape,
        indicatorPolygons = polygons,
    )

// TODO Update the docs images to point to the loading indicator.

/**
 * A Material Design loading indicator.
 *
 * This version of the loading indicator animates and morphs between various shapes as long as the
 * loading indicator is visible.
 *
 * ![Loading indicator
 * image](https://developer.android.com/images/reference/androidx/compose/material3/loading-indicator.png)
 *
 * It can be created like this:
 *
 * @sample androidx.compose.material3.samples.LoadingIndicatorSample
 * @param modifier the [Modifier] to be applied to this loading indicator
 * @param color the loading indicator's color
 * @param polygons a list of [RoundedPolygon]s for the sequence of shapes this loading indicator
 *   will morph between. The loading indicator expects at least two items in that list.
 * @throws IllegalArgumentException if the [polygons] list holds less than two items
 *
 * @material3expressive
 */
@ExperimentalMaterial3ExpressiveApi
@Composable
public fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = LoadingIndicatorDefaults.indicatorColor,
    polygons: List<RoundedPolygon> = LoadingIndicatorDefaults.indeterminateIndicatorPolygons,
): Unit =
    LoadingIndicatorImpl(
        modifier = modifier,
        containerColor = Color.Unspecified,
        indicatorColor = color,
        containerShape = LoadingIndicatorDefaults.containerShape,
        indicatorPolygons = polygons,
    )

// TODO Update the docs images to point to the loading indicator

/**
 * A Material Design contained loading indicator.
 *
 * This version of the loading indicator morphs between its [polygons] shapes by the value of its
 * [progress]. The shapes in this variation are contained within a colored [containerShape].
 *
 * ![Contained loading indicator
 * image](https://developer.android.com/images/reference/androidx/compose/material3/contained-loading-indicator.png)
 *
 * It can be created like this:
 *
 * @sample androidx.compose.material3.samples.DeterminateContainedLoadingIndicatorSample
 *
 * It can also be used as an indicator for a
 * [androidx.compose.material3.pulltorefresh.PullToRefreshBox] like this:
 *
 * @sample androidx.compose.material3.samples.LoadingIndicatorPullToRefreshSample
 * @param progress the progress of this loading indicator, where 0.0 represents no progress and 1.0
 *   represents full progress. Values outside of this range are coerced into the range. The
 *   indicator will morph its shapes between the provided [polygons] according to the value of the
 *   progress.
 * @param modifier the [Modifier] to be applied to this loading indicator
 * @param containerColor the loading indicator's container color
 * @param indicatorColor the loading indicator's color
 * @param containerShape the loading indicator's container shape
 * @param polygons a list of [RoundedPolygon]s for the sequence of shapes this loading indicator
 *   will morph between as it progresses from 0.0 to 1.0. The loading indicator expects at least two
 *   items in that list.
 * @throws IllegalArgumentException if the [polygons] list holds less than two items
 *
 * @material3expressive
 */
@ExperimentalMaterial3ExpressiveApi
@Composable
public fun ContainedLoadingIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    containerColor: Color = LoadingIndicatorDefaults.containedContainerColor,
    indicatorColor: Color = LoadingIndicatorDefaults.containedIndicatorColor,
    containerShape: Shape = LoadingIndicatorDefaults.containerShape,
    polygons: List<RoundedPolygon> = LoadingIndicatorDefaults.determinateIndicatorPolygons,
): Unit =
    LoadingIndicatorImpl(
        progress = progress,
        modifier = modifier,
        containerColor = containerColor,
        indicatorColor = indicatorColor,
        containerShape = containerShape,
        indicatorPolygons = polygons,
    )

// TODO Update the docs images to point to the loading indicator.

/**
 * A Material Design contained loading indicator.
 *
 * This version of the loading indicator animates and morphs between various shapes as long as the
 * loading indicator is visible. The shapes in this variation are contained within a colored
 * [containerShape].
 *
 * ![Contained loading indicator
 * image](https://developer.android.com/images/reference/androidx/compose/material3/contained-loading-indicator.png)
 *
 * It can be created like this:
 *
 * @sample androidx.compose.material3.samples.ContainedLoadingIndicatorSample
 * @param modifier the [Modifier] to be applied to this loading indicator
 * @param containerColor the loading indicator's container color
 * @param indicatorColor the loading indicator's color
 * @param containerShape the loading indicator's container shape
 * @param polygons a list of [RoundedPolygon]s for the sequence of shapes this loading indicator
 *   will morph between. The loading indicator expects at least two items in that list.
 * @throws IllegalArgumentException if the [polygons] list holds less than two items
 *
 * @material3expressive
 */
@ExperimentalMaterial3ExpressiveApi
@Composable
public fun ContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    containerColor: Color = LoadingIndicatorDefaults.containedContainerColor,
    indicatorColor: Color = LoadingIndicatorDefaults.containedIndicatorColor,
    containerShape: Shape = LoadingIndicatorDefaults.containerShape,
    polygons: List<RoundedPolygon> = LoadingIndicatorDefaults.indeterminateIndicatorPolygons,
): Unit =
    LoadingIndicatorImpl(
        modifier = modifier,
        containerColor = containerColor,
        indicatorColor = indicatorColor,
        containerShape = containerShape,
        indicatorPolygons = polygons,
    )
