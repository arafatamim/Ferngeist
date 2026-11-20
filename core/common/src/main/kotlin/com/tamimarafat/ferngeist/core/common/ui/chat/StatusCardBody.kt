package com.tamimarafat.ferngeist.core.common.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.tamimarafat.ferngeist.core.model.TurnStatus

/**
 * A card body for rows with no avatar to carry the turn. At rest the [stamp] sits centred beside
 * the text. While the turn needs showing it rises onto the [headline]'s baseline and a line grows
 * out of the [supporting] text into the space it left, to the card's end: a drifting wave while
 * working, still and recoloured while awaiting the user or failed. Without [supporting] text the
 * line runs alone; a [footer] sits beneath, clear of the stamp.
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
    val raise by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "statusStampRaise",
    )
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
                    alignment = Alignment.TopStart,
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
        val supportingWidth = lerp(narrow, constraints.maxWidth, raise.coerceIn(0f, 1f)).coerceAtLeast(0)
        val supportingPlaceable = supportings.first().measure(loose.copy(maxWidth = supportingWidth))
        val footerPlaceable = footers.firstOrNull()?.measure(loose.copy(maxWidth = narrow))
        val textHeight = headlinePlaceable.height + supportingPlaceable.height + (footerPlaceable?.height ?: 0)
        val height = maxOf(textHeight, stampPlaceable?.height ?: 0)
        layout(constraints.maxWidth, height) {
            headlinePlaceable.placeRelative(0, 0)
            supportingPlaceable.placeRelative(0, headlinePlaceable.height)
            footerPlaceable?.placeRelative(0, headlinePlaceable.height + supportingPlaceable.height)
            stampPlaceable?.let {
                val centred = (height - it.height) / 2
                val onHeadline = (headlinePlaceable[FirstBaseline] - it[FirstBaseline]).coerceAtLeast(0)
                it.placeRelative(constraints.maxWidth - it.width, lerp(centred, onHeadline, raise))
            }
        }
    }
}

@Composable
private fun SupportingWithLine(
    line: StatusLineState,
    supporting: (@Composable () -> Unit)?,
) {
    Row(
        // A breath under the title; the line otherwise starts on the title's descender. Skipped
        // when the row is empty, so a card at rest gains no dead space.
        modifier = Modifier.padding(top = if (supporting != null || line.shown) titleGap else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        supporting?.invoke()
        StatusWaveLine(
            state = line,
            modifier = Modifier.weight(1f, fill = false),
            startPadding = if (supporting != null) stampGap else 0.dp,
        )
    }
}

private val stampGap = 12.dp
private val titleGap = 4.dp
