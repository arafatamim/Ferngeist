package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

private const val SHIMMER_START_OFFSET = -200f
private const val SHIMMER_END_OFFSET = 600f

@Composable
fun rememberShimmerTextBrush(
    isActive: Boolean,
    baseColor: Color,
    labelPrefix: String,
    highlightColor: Color? = null,
    textWidth: Float? = null,
): Brush {
    if (!isActive) return SolidColor(baseColor)
    val shimmerTransition = rememberInfiniteTransition(label = "${labelPrefix}Shimmer")
    val shimmerOffset =
        shimmerTransition
            .animateFloat(
                initialValue = if (textWidth == null) SHIMMER_START_OFFSET else 0f,
                targetValue = if (textWidth == null) SHIMMER_END_OFFSET else 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation =
                            tween(
                                durationMillis = if (textWidth == null) 1400 else 2200,
                                easing = LinearEasing,
                            ),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "${labelPrefix}ShimmerOffset",
            ).value
    // Keep the highlight over short labels instead of spending most of the cycle off-text.
    val bandWidth = (textWidth ?: 0f) * 0.7f
    val start =
        if (textWidth == null) {
            shimmerOffset - SHIMMER_START_OFFSET
        } else {
            -bandWidth + shimmerOffset * (textWidth + bandWidth)
        }

    return Brush.linearGradient(
        colors =
            if (highlightColor == null) {
                listOf(baseColor.copy(alpha = 0.45f), baseColor.copy(alpha = 0.95f), baseColor.copy(alpha = 0.45f))
            } else {
                listOf(baseColor, highlightColor, baseColor)
            },
        start = Offset(start, 0f),
        end = Offset(if (textWidth == null) shimmerOffset else start + bandWidth, 0f),
    )
}
