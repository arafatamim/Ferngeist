package com.tamimarafat.ferngeist.feature.serverlist.ui.cards

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.chat.StatusWaveLine
import com.tamimarafat.ferngeist.core.common.ui.chat.rememberStatusLineState
import com.tamimarafat.ferngeist.core.model.TurnStatus

/** Between a card's status label and the line that follows it. */
private val labelGap = 12.dp

/**
 * A hero card's status label and its turn's wave line as one row. The label rises into its slot and
 * sinks out of it while fading, so a turn starting or ending reads as the text arriving or leaving
 * rather than a width appearing and the line being pushed across in a single frame. The gap belongs
 * to the label, so the line slides the last 12dp itself instead of snapping through them.
 *
 * The line cannot hold a fixed x while sharing this row — its start is the label's width — so it
 * follows the label's resize. Pinning it would mean reserving an empty slot the width of a label on
 * every card, including the ones at rest.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusLabelLine(
    label: String?,
    status: TurnStatus,
    modifier: Modifier = Modifier,
) {
    // Read here: a transition spec runs outside composition, where the theme is not reachable.
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val resize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val state = rememberStatusLineState(status)
    // The band is a label line tall for as long as a mark shows, so a label leaving does not shorten
    // the row and lift the name under it; at rest it still takes no space.
    val labelStyle = MaterialTheme.typography.labelLargeEmphasized
    val band = with(LocalDensity.current) { labelStyle.lineHeight.toDp() }
    Row(
        modifier = modifier.heightIn(min = if (state.shown) band else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                val enter = fadeIn(fade) + slideInVertically(slide) { it }
                val exit = fadeOut(fade) + slideOutVertically(slide) { it }
                // Unclipped: the label is drawn where it slides, not cut at the row's edge.
                (enter togetherWith exit)
                    .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> resize }))
            },
            contentAlignment = Alignment.CenterStart,
            label = "statusLabel",
        ) { text ->
            if (text != null) {
                Text(
                    text = text,
                    style = labelStyle,
                    modifier = Modifier.padding(end = labelGap),
                )
            }
        }
        StatusWaveLine(
            state = state,
            modifier = Modifier.weight(1f, fill = false),
            // The card's own ink: the turn's hue is chosen for the surface, and a tinted card hides it.
            color = waveLineColor,
        )
    }
}
