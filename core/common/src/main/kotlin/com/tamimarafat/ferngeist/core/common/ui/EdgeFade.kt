package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
// Samples sit at i / 64, the even spacing the list overload of verticalGradient assumes.
private val fadeAlphaCurve: List<Float> =
    (0..64).map { i ->
        val x = i / 64f
        1f - x * x * x * (6f * x * x - 15f * x + 10f)
    }

/**
 * A gradient of [fadeColor] sampled along [fadeAlphaCurve], opaque at the band's viewport edge.
 * [atEnd] reverses the samples so the band is opaque at the far edge (bottom or right) instead of
 * the near one.
 */
private fun fadeBrush(
    fadeColor: Color,
    orientation: Orientation,
    atEnd: Boolean,
    rtl: Boolean,
): Brush {
    // A horizontal gradient runs left to right; an RTL row's opaque edge is mirrored with it.
    val reverse = if (orientation == Orientation.Vertical) atEnd else atEnd != rtl
    val alphas = if (reverse) fadeAlphaCurve.map { alpha -> 1f - alpha } else fadeAlphaCurve
    val colors = alphas.map { alpha -> fadeColor.copy(alpha = alpha) }
    return if (orientation == Orientation.Vertical) {
        Brush.verticalGradient(colors)
    } else {
        Brush.horizontalGradient(colors)
    }
}

/**
 * Fades scrollable content edges into the surrounding surface by overlaying a
 * gradient of [fadeColor] (the surface color) over the content. The content
 * appears to dissolve into the surface at the viewport edges, with no tint
 * band edge and no visible line: the overlay's alpha follows a smoothstep
 * curve (zero slope at both ends), so the fade's end blends imperceptibly.
 *
 * The band size is scroll-coupled: it grows only as far as the content has
 * actually scrolled under the edge (capped at [edgeHeight]). This keeps the
 * fade band always over content — never over empty viewport, which would
 * render as a solid color block with a hard edge where the content begins.
 *
 * Call inside the [BoxScope] that wraps the scrollable content, after it.
 *
 * Use [edgeFadeMask] instead when the background behind the content is not a color you can
 * name — an app bar whose container tints as the page scrolls.
 *
 * @param scrollState The scroll state of the content to fade.
 * @param fadeColor The surface color the content fades into (typically the
 *   background behind the list).
 * @param edgeHeight Maximum size of each fade band along [orientation].
 * @param orientation The scroll axis: vertical fades the top and bottom edges,
 *   horizontal the left and right ones.
 */
@Composable
fun BoxScope.EdgeFade(
    scrollState: ScrollState,
    fadeColor: Color,
    edgeHeight: Dp = 22.dp,
    orientation: Orientation = Orientation.Vertical,
) {
    val edgePx = with(LocalDensity.current) { edgeHeight.toPx() }
    // Band grows with scroll so it always covers content, never empty viewport.
    // FrequentlyChangingValue: scrollState.value changes every frame; derivedStateOf
    // throttles recomposition to actual band-size changes instead of every pixel.
    val startBandPx = remember(edgePx) { derivedStateOf { min(scrollState.value.toFloat(), edgePx) } }
    val endBandPx =
        remember(edgePx) { derivedStateOf { min((scrollState.maxValue - scrollState.value).toFloat(), edgePx) } }
    EdgeFadeBands(
        scrollState.canScrollBackward,
        scrollState.canScrollForward,
        startBandPx,
        endBandPx,
        fadeColor,
        orientation,
    )
}

/** [EdgeFade] for a lazy list, which only knows the offsets of the items it has laid out. */
@Composable
fun BoxScope.EdgeFade(
    listState: LazyListState,
    fadeColor: Color,
    edgeHeight: Dp = 22.dp,
    orientation: Orientation = Orientation.Vertical,
) {
    val edgePx = with(LocalDensity.current) { edgeHeight.toPx() }
    val bands = rememberLazyBands(listState, edgePx)
    EdgeFadeBands(
        listState.canScrollBackward,
        listState.canScrollForward,
        bands.start,
        bands.end,
        fadeColor,
        orientation,
    )
}

/**
 * Fades the content's edges to transparent instead of painting a surface color over them.
 *
 * Unlike [EdgeFade], the erased pixels reveal whatever actually sits behind the content, so the
 * dissolve matches a background whose color changes — the top app bar tints toward its scrolled
 * container as the page scrolls — without a second source of truth for that color to drift from.
 *
 * Uses the same curve and scroll-coupled band sizing as [EdgeFade], so the two read alike.
 */
@Composable
fun Modifier.edgeFadeMask(
    listState: LazyListState,
    edgeSize: Dp = 22.dp,
    orientation: Orientation = Orientation.Vertical,
): Modifier {
    val edgePx = with(LocalDensity.current) { edgeSize.toPx() }
    val bands = rememberLazyBands(listState, edgePx)
    val startAlpha =
        animateFloatAsState(
            targetValue = if (listState.canScrollBackward) 1f else 0f,
            animationSpec = tween(300),
            label = "startMaskAlpha",
        )
    val endAlpha =
        animateFloatAsState(
            targetValue = if (listState.canScrollForward) 1f else 0f,
            animationSpec = tween(300),
            label = "endMaskAlpha",
        )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Black is only the eraser's alpha carrier; BlendMode.DstOut ignores its color.
    val startBrush = remember(orientation, rtl) { fadeBrush(Color.Black, orientation, atEnd = false, rtl) }
    val endBrush = remember(orientation, rtl) { fadeBrush(Color.Black, orientation, atEnd = true, rtl) }
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawEdgeErase(bands.start.value, startAlpha.value, startBrush, orientation, atStart = true)
            drawEdgeErase(bands.end.value, endAlpha.value, endBrush, orientation, atStart = false)
        }
}

/** One erased band: opaque at its viewport edge, clear at the band's inner end. */
private fun DrawScope.drawEdgeErase(
    bandPx: Float,
    bandAlpha: Float,
    brush: Brush,
    orientation: Orientation,
    atStart: Boolean,
) {
    if (bandPx <= 0f || bandAlpha <= 0f) return
    val bandSize =
        if (orientation == Orientation.Vertical) {
            Size(size.width, bandPx)
        } else {
            Size(bandPx, size.height)
        }
    // Brushes resolve against DrawScope.size, so inset both the origin and size to the band.
    val x = if (!atStart && orientation == Orientation.Horizontal) size.width - bandPx else 0f
    val y = if (!atStart && orientation == Orientation.Vertical) size.height - bandPx else 0f
    inset(left = x, top = y, right = size.width - x - bandSize.width, bottom = size.height - y - bandSize.height) {
        drawRect(brush = brush, alpha = bandAlpha, blendMode = BlendMode.DstOut)
    }
}

/** The two band sizes a lazy list needs, in pixels: one per viewport edge. */
private class EdgeBands(
    val start: State<Float>,
    val end: State<Float>,
)

@Composable
private fun rememberLazyBands(
    listState: LazyListState,
    edgePx: Float,
): EdgeBands =
    EdgeBands(
        start =
            remember(edgePx) {
                derivedStateOf {
                    if (listState.firstVisibleItemIndex >
                        0
                    ) {
                        edgePx
                    } else {
                        min(listState.firstVisibleItemScrollOffset.toFloat(), edgePx)
                    }
                }
            },
        end =
            remember(edgePx) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    when {
                        last == null -> 0f
                        last.index < info.totalItemsCount - 1 -> edgePx
                        else -> (last.offset + last.size - info.viewportEndOffset).toFloat().coerceIn(0f, edgePx)
                    }
                }
            },
    )

@Composable
private fun BoxScope.EdgeFadeBands(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    startBand: State<Float>,
    endBand: State<Float>,
    fadeColor: Color,
    orientation: Orientation,
) {
    val startAlpha by animateFloatAsState(
        targetValue = if (canScrollBackward) 1f else 0f,
        animationSpec = tween(300),
        label = "startFadeAlpha",
    )
    val endAlpha by animateFloatAsState(
        targetValue = if (canScrollForward) 1f else 0f,
        animationSpec = tween(300),
        label = "endFadeAlpha",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val startBrush = remember(fadeColor, orientation, rtl) { fadeBrush(fadeColor, orientation, atEnd = false, rtl) }
    val endBrush = remember(fadeColor, orientation, rtl) { fadeBrush(fadeColor, orientation, atEnd = true, rtl) }
    // Bands pin to the viewport edge they fade from, per scroll axis.
    val (startEdge, endEdge) =
        if (orientation == Orientation.Vertical) {
            Alignment.TopCenter to Alignment.BottomCenter
        } else {
            Alignment.CenterStart to Alignment.CenterEnd
        }
    EdgeFadeBand(startBand, startAlpha, startBrush, orientation, startEdge)
    EdgeFadeBand(endBand, endAlpha, endBrush, orientation, endEdge)
}

/** One overlay band, stretched across the content and [bandPx] deep from [edge]. */
@Composable
private fun BoxScope.EdgeFadeBand(
    bandPx: State<Float>,
    bandAlpha: Float,
    brush: Brush,
    orientation: Orientation,
    edge: Alignment,
) {
    val band by bandPx
    // matchParentSize, not fillMax*: the band must cover the content's measured cross size, and
    // filling the parent's constraint instead would inflate a wrap-content Box.
    Box(Modifier.matchParentSize().graphicsLayer { alpha = bandAlpha }) {
        Box(
            modifier =
                with(LocalDensity.current) {
                    when (orientation) {
                        Orientation.Vertical -> Modifier.fillMaxWidth().height(band.toDp())
                        Orientation.Horizontal -> Modifier.fillMaxHeight().width(band.toDp())
                    }
                }.align(edge)
                    .background(brush),
        )
    }
}
