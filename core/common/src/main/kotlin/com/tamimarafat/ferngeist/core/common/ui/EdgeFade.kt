package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Dense samples of the overlay alpha curve: alpha(x) = 1 - (6x^5 - 15x^4 +
 * 10x^3), where x runs 0 (viewport edge) to 1 (band end). Opaque at the
 * viewport edge (content hidden), transparent at the band end (content
 * revealed) — the content appears to dissolve INTO the surface at the edge.
 *
 * This is the quintic smootherstep: both the first AND second derivatives are
 * zero at both ends. The cubic smoothstep (3x^2 - 2x^3) leaves a non-zero
 * second derivative at the endpoints, which the eye reads as a faint seam
 * (Mach band) exactly where the fade terminates. The quintic curve's flat
 * curvature at both ends removes that edge.
 */
@Suppress("MagicNumber")
private val FADE_ALPHA_CURVE: List<Pair<Float, Float>> =
    (0..64).map { i ->
        val x = i / 64f
        x to (1f - x * x * x * (6f * x * x - 15f * x + 10f))
    }

private fun fadeBrush(fadeColor: Color): Brush =
    Brush.verticalGradient(
        *FADE_ALPHA_CURVE.map { (fraction, alpha) -> fraction to fadeColor.copy(alpha = alpha) }.toTypedArray(),
    )

private fun bottomFadeBrush(fadeColor: Color): Brush =
    Brush.verticalGradient(
        *FADE_ALPHA_CURVE.map { (fraction, alpha) -> fraction to fadeColor.copy(alpha = 1f - alpha) }.toTypedArray(),
    )

/**
 * Fades scrollable content edges into the surrounding surface by overlaying a
 * gradient of [fadeColor] (the surface color) over the content. The content
 * appears to dissolve into the surface at the viewport edges, with no tint
 * band edge and no visible line: the overlay's alpha follows a smoothstep
 * curve (zero slope at both ends), so the fade's end blends imperceptibly.
 *
 * The band height is scroll-coupled: it grows only as far as the content has
 * actually scrolled under the edge (capped at [edgeHeight]). This keeps the
 * fade band always over content — never over empty viewport, which would
 * render as a solid color block with a hard edge where the content begins.
 *
 * Call inside the [BoxScope] that wraps the scrollable content, after it.
 *
 * @param scrollState The scroll state of the content to fade.
 * @param fadeColor The surface color the content fades into (typically the
 *   background behind the list).
 * @param edgeHeight Maximum height of each fade band.
 */
@Composable
fun BoxScope.EdgeFade(
    scrollState: ScrollState,
    fadeColor: Color,
    edgeHeight: Dp = 22.dp,
) {
    val edgePx = with(LocalDensity.current) { edgeHeight.toPx() }
    val topAlpha by animateFloatAsState(
        targetValue = if (scrollState.canScrollBackward) 1f else 0f,
        animationSpec = tween(300),
        label = "topFadeAlpha",
    )
    val bottomAlpha by animateFloatAsState(
        targetValue = if (scrollState.canScrollForward) 1f else 0f,
        animationSpec = tween(300),
        label = "bottomFadeAlpha",
    )
    // Band grows with scroll so it always covers content, never empty viewport.
    val topBandPx = min(scrollState.value.toFloat(), edgePx)
    val bottomBandPx = min((scrollState.maxValue - scrollState.value).toFloat(), edgePx)
    val density = LocalDensity.current
    val brush = remember(fadeColor) { fadeBrush(fadeColor) }
    val bottomBrush = remember(fadeColor) { bottomFadeBrush(fadeColor) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(with(density) { topBandPx.toDp() })
                .graphicsLayer { alpha = topAlpha }
                .background(brush),
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(with(density) { bottomBandPx.toDp() })
                .align(Alignment.BottomCenter)
                .graphicsLayer { alpha = bottomAlpha }
                .background(bottomBrush),
    )
}
