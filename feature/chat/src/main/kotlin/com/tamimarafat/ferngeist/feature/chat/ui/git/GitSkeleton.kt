package com.tamimarafat.ferngeist.feature.chat.ui.git

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.tamimarafat.ferngeist.feature.chat.R

private const val PULSE_MILLIS = 900
private const val PULSE_LOW = 0.45f

/**
 * Pulses [content] as one layer, so every bone breathes in phase and the animation costs a
 * single alpha change per frame. Announced once as "Loading" instead of as empty shapes.
 */
@Composable
internal fun SkeletonPulse(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pulse =
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 1f,
            targetValue = PULSE_LOW,
            animationSpec = infiniteRepeatable(tween(PULSE_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "skeletonAlpha",
        )
    val loading = stringResource(R.string.chat_git_loading)
    Box(
        modifier =
            modifier
                .graphicsLayer { alpha = pulse.value }
                .clearAndSetSemantics { contentDescription = loading },
    ) {
        content()
    }
}

/** A placeholder shape standing in for text or an icon. Width null fills what the modifier allows. */
@Composable
internal fun Bone(
    height: Dp,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    shape: Shape = RoundedCornerShape(percent = 50),
) {
    Box(
        modifier =
            modifier
                .then(if (width != null) Modifier.width(width) else Modifier)
                .height(height)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
    )
}

/** Varied but stable line lengths, so a skeleton looks like text rather than a barcode. */
internal val boneFractions = listOf(0.72f, 0.5f, 0.86f, 0.62f, 0.4f, 0.78f, 0.56f, 0.9f)
