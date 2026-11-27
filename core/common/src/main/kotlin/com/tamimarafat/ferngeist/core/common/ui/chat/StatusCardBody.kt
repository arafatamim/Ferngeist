package com.tamimarafat.ferngeist.core.common.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.util.lerp
import com.tamimarafat.ferngeist.core.common.ui.status.waveLineColor
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlin.math.roundToInt

/**
 * A card body for rows with no avatar to carry the turn. At rest the [stamp] sits centred beside
 * the text. While the turn needs showing, a line grows out of the [supporting] text to the card's
 * end. The stamp centres beside a wrapped [headline], or rises to a single-line headline's
 * baseline if it overlaps the line's band. The line drifts while working and stays still and
 * recoloured while awaiting the user or failed. Without [supporting] text the line runs alone.
 * A [footer] sits beneath, clear of the stamp. A finished
 * turn's dot is a marker, not a line: it stays put in the line's band while the title and footer
 * close in around it, as they sit at rest. Both stop short of the stamp's column, which is where
 * the dot is drawn.
 */
@Composable
fun StatusCardBody(
    status: TurnStatus,
    headline: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)?,
    stamp: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    val line = rememberStatusLineState(status)
    val shown = line.shown
    val expand by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "statusBandExpand",
    )
    val lineSpacing by animateDpAsState(
        targetValue = if (line.lined) extraLineGap else 0.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "statusLineSpacing",
    )
    // 0 pins the title to the top of the body, above a line's band; 1 centres it in the body.
    val centring by animateFloatAsState(
        targetValue = if (supporting == null && !line.lined) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "statusTitleCentring",
    )
    // Centred, the content stays centred while the box resizes, so a dot's band leaving with
    // it does not move the title it already sits level with.
    val sizeAlignment =
        remember {
            Alignment { size, space, _ -> IntOffset(0, ((space.height - size.height) * centring / 2).roundToInt()) }
        }
    Layout(
        contents =
            listOf(
                headline,
                {
                    SupportingWithLine(line = line, supporting = supporting)
                },
                stamp,
                footer,
            ),
        // The card row centres this body against its avatar, so a height snap would move the title
        // in one frame while the stamp's raise glides. Animating the size keeps the two in step.
        modifier =
            modifier
                .reserveDotOvershoot()
                .animateContentSize(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    alignment = sizeAlignment,
                ).padding(end = dotOvershootSpace),
    ) { slots, constraints ->
        val (headlines, supportings, stamps) = slots
        val footers = slots.last()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val stampPlaceable = stamps.firstOrNull()?.measure(loose)
        val narrow =
            (constraints.maxWidth - (stampPlaceable?.let { it.width + stampGap.roundToPx() } ?: 0)).coerceAtLeast(0)
        val headlinePlaceable = headlines.first().measure(loose.copy(maxWidth = narrow))
        // The spring overshoots on purpose; the line's width must not pass the card's edge.
        val supportingWidth = lerp(narrow, constraints.maxWidth, expand.coerceIn(0f, 1f)).coerceAtLeast(0)
        // Keep the line clear of the title's descenders and leave empty rows flush.
        val lineGap = lineSpacing.roundToPx().coerceIn(0, extraLineGap.roundToPx())
        val titleSpacing = titleGap.roundToPx() + lineGap
        val bandGap = if (supporting != null || shown) titleSpacing.coerceAtMost(loose.maxHeight) else 0
        val supportingPlaceable =
            supportings.first().measure(loose.offset(vertical = -bandGap).copy(maxWidth = supportingWidth))
        val footerPlaceable = footers.firstOrNull()?.measure(loose.copy(maxWidth = narrow))
        val bandTop = headlinePlaceable.height + bandGap
        val bandBottom = bandTop + supportingPlaceable.height
        val textHeight = bandBottom + (footerPlaceable?.let { lineGap + it.height } ?: 0)
        val height = maxOf(textHeight, stampPlaceable?.height ?: 0)
        // The band stays where a line ran; the title drops and the footer rises into it by half.
        val drop = ((bandGap + supportingPlaceable.height) * centring.coerceIn(0f, 1f) / 2).roundToInt()
        layout(constraints.maxWidth, height) {
            headlinePlaceable.placeRelative(0, drop)
            supportingPlaceable.placeRelative(0, bandTop)
            footerPlaceable?.placeRelative(0, bandBottom + lineGap - drop)
            stampPlaceable?.let {
                val centred = (height - it.height) / 2
                val aligned = activeStampTop(headlinePlaceable, it, bandTop until bandBottom, centred, drop)
                val y = lerp(centred, aligned, expand)
                it.placeRelative(constraints.maxWidth - it.width, y)
            }
        }
    }
}

private fun activeStampTop(
    headline: Placeable,
    stamp: Placeable,
    band: IntRange,
    centred: Int,
    titleDrop: Int,
): Int {
    val firstBaseline = headline[FirstBaseline]
    val lastBaseline = headline[LastBaseline]
    val overlapsBand = !band.isEmpty() && centred <= band.last && centred + stamp.height > band.first
    return when {
        lastBaseline > firstBaseline -> titleDrop + (headline.height - stamp.height) / 2
        // A dot can sit beside a dropped title while the stamp stays above it.
        overlapsBand -> firstBaseline - stamp[FirstBaseline]
        else -> centred
    }.coerceAtLeast(0)
}

@Composable
private fun SupportingWithLine(
    line: StatusLineState,
    supporting: (@Composable () -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        supporting?.invoke()
        StatusWaveLine(
            state = line,
            color = waveLineColor,
            modifier = Modifier.weight(1f, fill = false),
            startPadding = if (supporting != null) stampGap else 0.dp,
        )
    }
}

private val stampGap = 12.dp
private val titleGap = 4.dp
private val extraLineGap = 4.dp
