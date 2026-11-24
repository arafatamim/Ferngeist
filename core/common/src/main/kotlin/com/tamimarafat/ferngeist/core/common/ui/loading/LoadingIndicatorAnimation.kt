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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class LoadingIndicatorAnimation(
    val morphProgress: Animatable<Float, AnimationVector1D>,
    val turnProgress: Animatable<Float, AnimationVector1D>,
    val globalRotation: Animatable<Float, AnimationVector1D>,
    val radialScale: Animatable<Float, AnimationVector1D>,
    rotationTarget: MutableFloatState,
) {
    var morphRotationTargetAngle by rotationTarget
    var currentMorphIndex by mutableIntStateOf(0)

    val rotationDegrees: Float
        get() =
            turnProgress.value * LoadingIndicatorReference.turnDegrees(currentMorphIndex) +
                morphRotationTargetAngle + globalRotation.value
}

@Composable
internal fun rememberLoadingIndicatorAnimation(
    polygons: List<RoundedPolygon>,
    morphCount: Int,
): LoadingIndicatorAnimation {
    val morphProgress = remember { Animatable(0f) }
    val turnProgress = remember { Animatable(0f) }
    val globalRotation = remember { Animatable(0f) }
    val radialScale = remember { Animatable(1f) }
    // Start on SoftBurst's own orientation, where every seven-beat loop of the spec begins.
    val rotationTarget = remember { mutableFloatStateOf(0f) }
    // The upstream implementation resets only the active index when polygons change.
    val animation =
        remember(polygons) {
            LoadingIndicatorAnimation(morphProgress, turnProgress, globalRotation, radialScale, rotationTarget)
        }
    LaunchedEffect(polygons) {
        // Possibly skip the infinite animation block when an InfiniteAnimationPolicy is installed.
        val policy = coroutineContext[InfiniteAnimationPolicy]
        when (policy) {
            null -> animation.animate(morphCount)
            else -> policy.onInfiniteOperation { animation.animate(morphCount) }
        }
    }
    return animation
}

private suspend fun LoadingIndicatorAnimation.animate(morphCount: Int) =
    coroutineScope {
        launch { animateMorphs(morphCount) }
        launch {
            globalRotation.animateTo(
                targetValue = FULL_ROTATION,
                animationSpec =
                    infiniteRepeatable(
                        tween(GLOBAL_ROTATION_DURATION_MILLIS, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
            )
        }
    }

private suspend fun LoadingIndicatorAnimation.animateMorphs(morphCount: Int) =
    coroutineScope {
        while (true) {
            // Keep the upstream beat, awaiting the morph when system duration scaling slows it.
            // Radial spring children belong to this scope so their tails can cross the next beat.
            val deferred = async { animateMorph(morphCount, this@coroutineScope) }
            delay(MORPH_INTERVAL_MILLIS)
            deferred.await()
        }
    }

private suspend fun LoadingIndicatorAnimation.animateMorph(
    morphCount: Int,
    motionScope: CoroutineScope,
) {
    // Fitted to the spec video: the turn leads with an eased swing whose slight overshoot settles
    // without outrunning the global spin backwards, the morph spring follows
    // MORPH_START_DELAY_MILLIS later, and a radial pop rides on the morph. All settle within the beat.
    val turnAnimationSpec = tween<Float>(TURN_DURATION_MILLIS, easing = CubicBezierEasing(0.42f, 0.44f, 0.4f, 1.16f))
    val morphAnimationSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 715f, visibilityThreshold = 0.01f)
    val radialAnimationSpec = spring<Float>(dampingRatio = 0.49f, stiffness = 260f, visibilityThreshold = 0.0001f)
    val impulse = LoadingIndicatorReference.POP_IMPULSE
    val durationScale = currentCoroutineContext()[MotionDurationScale]
    var radialImpulseApplied = false
    val turn = motionScope.async { turnProgress.animateTo(targetValue = 1f, animationSpec = turnAnimationSpec) }
    delay(MORPH_START_DELAY_MILLIS)
    val result =
        morphProgress.animateTo(targetValue = 1f, animationSpec = morphAnimationSpec) {
            if (!radialImpulseApplied && value >= RADIAL_IMPULSE_PROGRESS && durationScale?.scaleFactor != 0f) {
                radialImpulseApplied = true
                // Transfer early-morph momentum without resetting the radial spring's position or velocity.
                motionScope.launch {
                    radialScale.animateTo(
                        targetValue = 1f,
                        animationSpec = radialAnimationSpec,
                        initialVelocity = radialScale.velocity + impulse,
                    )
                }
            }
        }
    val turned = turn.await()
    if (result.endReason == AnimationEndReason.Finished && turned.endReason == AnimationEndReason.Finished) {
        val turnDegrees = LoadingIndicatorReference.turnDegrees(currentMorphIndex)
        currentMorphIndex = (currentMorphIndex + 1) % morphCount
        morphProgress.snapTo(0f)
        turnProgress.snapTo(0f)
        morphRotationTargetAngle = (morphRotationTargetAngle + turnDegrees) % FULL_ROTATION
    }
}

private const val FULL_ROTATION = 360f
private const val GLOBAL_ROTATION_DURATION_MILLIS = 4666

// The spec video loops its seven morphs over the 4.667 s rotation, so one beat is 4667 / 7 ms.
private const val MORPH_INTERVAL_MILLIS = 667L
private const val MORPH_START_DELAY_MILLIS = 84L
private const val TURN_DURATION_MILLIS = 385
private const val RADIAL_IMPULSE_PROGRESS = 0.1f
