package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest

private const val FULL_ROTATION_DEGREES = 360f
private const val MILLIS_PER_SECOND = 1_000f
private const val NANOS_PER_SECOND = 1_000_000_000f

/** Eases back to rest when inactive; disabled system motion suspends the frame loop. */
@Composable
fun rememberActivityRotation(
    periodMillis: Int,
    active: Boolean = true,
): State<Float> {
    val owner = LocalLifecycleOwner.current
    val rotation = remember { mutableFloatStateOf(0f) }
    val speed =
        animateFloatAsState(
            targetValue = if (active) FULL_ROTATION_DEGREES * MILLIS_PER_SECOND / periodMillis else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "activitySpeed",
        )
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(owner, active) {
        if (!active) {
            settleRotation(rotation, speed.value, settleSpec)
            return@LaunchedEffect
        }
        val durationScale = currentCoroutineContext()[MotionDurationScale]
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { durationScale?.scaleFactor ?: 1f }.collectLatest { scale ->
                if (scale > 0f) {
                    var previous = withFrameNanos { it }
                    while (true) {
                        withFrameNanos { frame ->
                            val seconds = (frame - previous) / NANOS_PER_SECOND
                            rotation.floatValue =
                                (rotation.floatValue + seconds * speed.value / scale) % FULL_ROTATION_DEGREES
                            previous = frame
                        }
                    }
                }
            }
        }
    }
    return rotation
}

private suspend fun settleRotation(
    rotation: MutableFloatState,
    speed: Float,
    spec: AnimationSpec<Float>,
) {
    val target = if (rotation.floatValue < FULL_ROTATION_DEGREES / 2f) 0f else FULL_ROTATION_DEGREES
    animate(
        initialValue = rotation.floatValue,
        targetValue = target,
        initialVelocity = speed,
        animationSpec = spec,
    ) { value, _ ->
        rotation.floatValue = value
    }
    rotation.floatValue = 0f
}
