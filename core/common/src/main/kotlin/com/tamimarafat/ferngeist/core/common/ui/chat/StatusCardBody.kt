package com.tamimarafat.ferngeist.core.common.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.tamimarafat.ferngeist.core.common.ui.status.showsMark
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusLabel
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.delay

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
    val line = remember { StatusLine(status) }
    val eatSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(status) { line.follow(status, eatSpec) }
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
                    SupportingWithLine(
                        shown = shown,
                        lineStatus = line.status,
                        amplitude = { if (line.flat) 0f else 1f },
                        eaten = { line.eaten.value },
                        supporting = supporting,
                    )
                },
                stamp,
                footer,
            ),
        // The card row centres this body against its avatar, so a height snap would move the title
        // in one frame while the stamp's raise glides. Animating the size keeps the two in step.
        modifier =
            modifier.animateContentSize(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                alignment = Alignment.TopStart,
            ),
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

/**
 * A turn leaving every lined state flattens the wave, holds it a beat, then eats it from the
 * left before the stamp settles. The indicator animates amplitude itself and drops new targets
 * mid-animation, so it gets only the end state, never a value we animate.
 */
@Stable
private class StatusLine(
    initial: TurnStatus,
) {
    var shown by mutableStateOf(initial.showsMark)
        private set
    var flat by mutableStateOf(initial != TurnStatus.WORKING)
        private set

    // The last lined state keeps the line's colour and spoken state while it leaves.
    var status by mutableStateOf(initial)
        private set
    val eaten = Animatable(0f)

    suspend fun follow(
        next: TurnStatus,
        eatSpec: AnimationSpec<Float>,
    ) {
        val wasFlat = flat
        flat = next != TurnStatus.WORKING
        if (next.showsMark) {
            // Reset only while hidden: a line left fully eaten must not reappear before it returns.
            eaten.snapTo(if (shown) eaten.value else 0f)
            status = next
            shown = true
            eaten.animateTo(0f, eatSpec)
        } else if (shown) {
            delay((if (wasFlat) 0L else FLATTEN_MILLIS) + LINGER_MILLIS)
            eaten.animateTo(1f, eatSpec)
            shown = false
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SupportingWithLine(
    shown: Boolean,
    lineStatus: TurnStatus,
    amplitude: () -> Float,
    eaten: () -> Float,
    supporting: (@Composable () -> Unit)?,
) {
    val description = turnStatusLabel(lineStatus)
    val color by animateColorAsState(
        targetValue = lineStatus.lineColor(),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "statusLineColor",
    )
    Row(
        // A breath under the title; the line otherwise starts on the title's descender. Skipped
        // when the row is empty, so a card at rest gains no dead space.
        modifier = Modifier.padding(top = if (supporting != null || shown) titleGap else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        supporting?.invoke()
        // fill = false: the line takes the leftover width only while visible, so it can grow into it.
        AnimatedVisibility(
            visible = shown,
            modifier = Modifier.weight(1f, fill = false),
            enter =
                expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec(), Alignment.Start) +
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            // The line has already been eaten away by the time it hides.
            exit = ExitTransition.None,
        ) {
            // Full and determinate, so the whole line is one unbroken wave that only drifts; the
            // indeterminate form sweeps segments across like a marquee. Our own amplitude overrides
            // the default, which flattens the wave near 100%.
            LinearWavyProgressIndicator(
                progress = { 1f },
                modifier =
                    Modifier
                        .padding(start = if (supporting != null) stampGap else 0.dp)
                        .fillMaxWidth()
                        // Clipped before the mirror, so "left" is the screen's left. Overshoot past 1
                        // only clips more.
                        .drawWithContent {
                            clipRect(left = size.width * eaten().coerceIn(0f, 1f)) {
                                this@drawWithContent.drawContent()
                            }
                        }
                        // Mirrored so the wave drifts back toward the text rather than away from it.
                        .graphicsLayer { scaleX = -1f }
                        .semantics { description?.let { stateDescription = it } },
                color = color,
                stopSize = 0.dp,
                amplitude = { amplitude() },
                wavelength = waveLength,
                waveSpeed = waveLength / WAVE_PERIOD_SECONDS,
            )
        }
    }
}

@Composable
private fun TurnStatus.lineColor(): Color =
    when (this) {
        // A solid primary tone a few steps nearer the surface in either theme (80 in light,
        // 40 in dark): the timer's hue, quieter than the title, without a faded look.
        TurnStatus.WORKING -> MaterialTheme.colorScheme.inversePrimary
        TurnStatus.NEEDS_ATTENTION -> MaterialTheme.colorScheme.tertiary
        TurnStatus.ERROR -> MaterialTheme.colorScheme.error
        TurnStatus.FINISHED, TurnStatus.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
    }

private val stampGap = 12.dp
private val titleGap = 4.dp
private val waveLength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength * 0.75f

// One wavelength every five seconds: present, never busy.
private const val WAVE_PERIOD_SECONDS = 5
private const val LINGER_MILLIS = 1_000L

// Material's own amplitude-decrease tween (WavyProgressIndicator's DecreasingAmplitudeAnimationSpec).
private const val FLATTEN_MILLIS = 500L
