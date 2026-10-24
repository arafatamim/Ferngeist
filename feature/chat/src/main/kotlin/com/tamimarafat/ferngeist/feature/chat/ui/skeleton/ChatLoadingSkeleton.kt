package com.tamimarafat.ferngeist.feature.chat.ui.skeleton

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.chat.ui.skeleton.ChatLoadingSkeleton
import kotlin.math.ceil

private const val SKELETON_SHIMMER_BAND_PX = 200f
private const val SKELETON_SHIMMER_MILLIS = 800

/** Ceiling on pattern repeats: unbounded constraints would otherwise loop forever. */
private const val SKELETON_MAX_PASSES = 16
private val skeletonLineHeight = 12.dp
private val skeletonLineGap = 8.dp

/** Gap between turns. Deliberately wider than [skeletonLineGap] so the pattern reads as separate messages. */
private val skeletonGroupGap = 20.dp
private val skeletonUserLine = listOf(1f)

// Assistant blocks stay between 4 and 7 lines: long enough to read as a response.
private val skeletonAssistantLinesPrimary = listOf(1f, 0.92f, 0.78f, 0.6f)
private val skeletonAssistantLinesExtended = listOf(1f, 0.95f, 0.88f, 0.97f, 0.9f, 0.86f, 0.5f)

/** One placeholder message: right-aligned and short for the user, full-width for the assistant. */
private data class SkeletonMessageSpec(
    val alignment: Alignment.Horizontal,
    val widthFraction: Float,
    val lineFractions: List<Float>,
)

private val skeletonPattern =
    listOf(
        SkeletonMessageSpec(Alignment.End, 0.5f, skeletonUserLine),
        SkeletonMessageSpec(Alignment.Start, 1f, skeletonAssistantLinesPrimary),
        SkeletonMessageSpec(Alignment.End, 0.38f, skeletonUserLine),
        SkeletonMessageSpec(Alignment.Start, 1f, skeletonAssistantLinesExtended),
        SkeletonMessageSpec(Alignment.End, 0.44f, skeletonUserLine),
        SkeletonMessageSpec(Alignment.Start, 1f, skeletonAssistantLinesPrimary),
    )

/** Height of one full pass of [skeletonPattern], including the gap that follows it. */
private val skeletonPatternHeight: Dp =
    skeletonPattern.fold(skeletonGroupGap * skeletonPattern.size) { total, message ->
        total +
            skeletonLineHeight * message.lineFractions.size +
            skeletonLineGap * (message.lineFractions.size - 1)
    }

/**
 * Shimmer skeleton shown while the first snapshot loads with an empty cache.
 * Mirrors the message list (user bubbles right, assistant bubbles left) so the
 * layout doesn't jump when real content paints. Only reachable on a true
 * cold open — cached transcripts render immediately instead.
 *
 * The pattern repeats until it covers the viewport and dissolves toward the
 * bottom, so the screen reads as "transcript on the way" rather than four
 * bubbles floating above empty space.
 */
@Composable
internal fun ChatLoadingSkeleton(
    listTopPadding: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        // Sweep the full measured width: a fixed pixel range leaves wide
        // screens half-static.
        val sweepWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val lineBrush = rememberSkeletonShimmerBrush(sweepWidthPx)
        val contentTopPadding = listTopPadding + 24.dp
        val passes =
            ceil((maxHeight - contentTopPadding) / skeletonPatternHeight)
                .toInt()
                .coerceIn(1, SKELETON_MAX_PASSES)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, top = contentTopPadding, end = 16.dp)
                    // Offscreen so the erase below cuts only the skeleton, not the
                    // chat surface drawn behind it.
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Transparent, Color.Black),
                                ),
                            blendMode = BlendMode.DstOut,
                        )
                    },
            verticalArrangement = Arrangement.spacedBy(skeletonGroupGap),
        ) {
            repeat(passes) {
                skeletonPattern.forEach { message ->
                    SkeletonBlock(
                        brush = lineBrush,
                        alignment = message.alignment,
                        widthFraction = message.widthFraction,
                        lineFractions = message.lineFractions,
                    )
                }
            }
        }
    }
}

/**
 * One skeleton message: bare shimmer text lines, right-aligned for user
 * messages and full-width for assistant ones.
 */
@Composable
private fun ColumnScope.SkeletonBlock(
    brush: Brush,
    alignment: Alignment.Horizontal,
    widthFraction: Float,
    lineFractions: List<Float>,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth(widthFraction)
                .align(alignment),
        verticalArrangement = Arrangement.spacedBy(skeletonLineGap),
    ) {
        lineFractions.forEach { fraction ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(skeletonLineHeight)
                        .background(brush, CircleShape),
            )
        }
    }
}

/**
 * Highlight band sweeping across the skeleton's full width, matching the
 * streaming-text shimmer's 1400ms rhythm so the skeleton hands off to
 * content seamlessly.
 */
@Composable
private fun rememberSkeletonShimmerBrush(sweepWidthPx: Float): Brush {
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val shimmer = rememberInfiniteTransition(label = "ChatSkeletonShimmer")
    val offset by shimmer.animateFloat(
        initialValue = -sweepWidthPx,
        targetValue = sweepWidthPx * 2,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SKELETON_SHIMMER_MILLIS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "ChatSkeletonShimmerOffset",
    )
    return Brush.linearGradient(
        colors =
            listOf(
                baseColor.copy(alpha = 0.55f),
                baseColor,
                baseColor.copy(alpha = 0.55f),
            ),
        start = Offset(offset - SKELETON_SHIMMER_BAND_PX, 0f),
        end = Offset(offset, 0f),
    )
}

@Preview
@Composable
private fun ChatLoadingSkeletonPreview() {
    Surface {
        ChatLoadingSkeleton(listTopPadding = 0.dp)
    }
}
