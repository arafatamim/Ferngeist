package com.tamimarafat.ferngeist.core.common.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.tamimarafat.ferngeist.core.common.ui.status.showsMark
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusLabel
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.delay

/** A [StatusLineState] that follows [status]; pair it with [StatusWaveLine]. */
@Composable
fun rememberStatusLineState(status: TurnStatus): StatusLineState {
    val line = remember { StatusLineState(status) }
    val eatSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(status) { line.follow(status, eatSpec) }
    return line
}

/**
 * A turn leaving every lined state flattens the wave, holds it a beat, then eats it from the
 * left before the line hides. The indicator animates amplitude itself and drops new targets
 * mid-animation, so it gets only the end state, never a value we animate.
 */
@Stable
class StatusLineState internal constructor(
    initial: TurnStatus,
) {
    var shown by mutableStateOf(initial.showsMark)
        private set
    var flat by mutableStateOf(initial != TurnStatus.WORKING)
        private set

    // A line keeps the title above its band; a finished turn's dot is a marker the title may sit beside.
    var lined by mutableStateOf(initial.showsMark && initial != TurnStatus.FINISHED)
        private set

    // The last lined state keeps the line's colour and spoken state while it leaves.
    var status by mutableStateOf(initial)
        private set
    val eaten = Animatable(0f)

    internal suspend fun follow(
        next: TurnStatus,
        eatSpec: AnimationSpec<Float>,
    ) {
        val wasFlat = flat
        flat = next != TurnStatus.WORKING
        if (next.showsMark) {
            // Reset only while hidden: a line left fully eaten must not reappear before it returns.
            eaten.snapTo(if (shown) eaten.value else 0f)
            // A dot already standing stays a dot; anything else arrives as a line.
            if (next != TurnStatus.FINISHED || !shown) lined = true
            status = next
            shown = true
            eaten.animateTo(0f, eatSpec)
            if (next == TurnStatus.FINISHED) {
                // rememberDotContraction's flatten and contraction: the line is a dot by then.
                delay(FLATTEN_MILLIS + CONTRACTION_MILLIS)
                lined = false
            }
        } else if (shown) {
            delay((if (wasFlat) 0L else FLATTEN_MILLIS) + LINGER_MILLIS)
            eaten.animateTo(1f, eatSpec)
            shown = false
            lined = false
        }
    }
}

/**
 * The turn as one wavy line: it grows out from its start edge into the width it is given, drifts
 * while working, stays flat while awaiting the user or failed, and contracts to a dot when finished.
 * Hidden, it takes no space; a parent can hand it leftover width with `weight(1f, fill = false)`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusWaveLine(
    state: StatusLineState,
    modifier: Modifier = Modifier,
    startPadding: Dp = 0.dp,
    color: Color = state.status.lineColor(),
) {
    val description = turnStatusLabel(state.status)
    // The turn's own hue is picked for the surface; a card that sets its own container passes ink.
    val tint by animateColorAsState(
        targetValue = color,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "statusLineColor",
    )
    val contraction = rememberDotContraction(state.status)
    val stroke = WavyProgressIndicatorDefaults.linearIndicatorStroke
    // fill = false in the parent: the line takes the leftover width only while visible, so it can grow into it.
    AnimatedVisibility(
        visible = state.shown,
        modifier =
            modifier.reserveDotOvershoot().drawContraction(
                state,
                contraction,
                color,
                stroke.width,
                startPadding,
            ),
        enter =
            expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec(), Alignment.Start, clip = false) +
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
                    .padding(start = startPadding, end = dotOvershootSpace)
                    .heightIn(min = 12.dp)
                    .fillMaxWidth()
                    // Clipped before the mirror, so "left" is the screen's left.
                    .drawWithContent {
                        if (contraction.value <= 0f) {
                            clipRect(left = size.width * state.eaten.value.coerceIn(0f, 1f)) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    }
                    // Mirrored so the wave drifts back toward the text rather than away from it.
                    .graphicsLayer { scaleX = -1f }
                    .semantics { description?.let { stateDescription = it } },
            color = tint,
            stroke = stroke,
            stopSize = 0.dp,
            amplitude = { if (state.flat) 0f else 1f },
            wavelength = waveLength,
            waveSpeed = waveLength / WAVE_PERIOD_SECONDS,
        )
    }
}

internal fun Modifier.reserveDotOvershoot(): Modifier =
    layout { measurable, constraints ->
        val extra = if (constraints.hasBoundedWidth) dotOvershootSpace.roundToPx() else 0
        val child =
            measurable.measure(
                constraints.copy(
                    minWidth = constraints.minWidth + extra,
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
                ),
            )
        layout((child.width - extra).coerceAtLeast(0), child.height) {
            child.placeRelative(0, 0)
        }
    }

private fun Modifier.drawContraction(
    state: StatusLineState,
    contraction: Animatable<Float, *>,
    color: Color,
    strokeWidth: Float,
    startPadding: Dp,
): Modifier =
    drawWithContent {
        drawContent()
        if (state.shown && contraction.value > 0f) {
            val padding = startPadding.toPx().coerceAtMost(size.width)
            val extra = dotOvershootSpace.toPx().coerceAtMost(size.width - padding)
            val left = if (layoutDirection == LayoutDirection.Ltr) padding else extra
            val right = if (layoutDirection == LayoutDirection.Rtl) padding else extra
            inset(left = left, top = 0f, right = right, bottom = 0f) {
                drawContractingLine(color, strokeWidth, contraction.value, state.eaten.value.coerceIn(0f, 1f))
            }
        }
    }

private fun DrawScope.drawContractingLine(
    color: Color,
    strokeWidth: Float,
    progress: Float,
    eaten: Float,
) {
    val dotRadius = 4.dp.toPx().coerceAtMost(size.width / 6f)
    val lineHalfWidth = size.width / 2f
    val centerX = lerp(lineHalfWidth, size.width - dotRadius, progress / DOT_HANDOFF)
    val halfWidth = lerp(lineHalfWidth, dotRadius, (progress / DOT_HANDOFF).coerceAtMost(1f))
    val rounding = ((progress - DOT_ROUNDING_START) / (DOT_HANDOFF - DOT_ROUNDING_START)).coerceIn(0f, 1f)
    val radius = lerp(strokeWidth / 2f, dotRadius, rounding).coerceAtMost(halfWidth)
    // The erasing clip must include the dot when it travels beyond the original line endpoint.
    val right = maxOf(size.width, centerX + halfWidth)
    clipRect(left = right * eaten, right = right) {
        drawRoundRect(
            color = color,
            topLeft = Offset(centerX - halfWidth, size.height / 2f - radius),
            size = Size(halfWidth * 2f, radius * 2f),
            cornerRadius = CornerRadius(radius),
        )
    }
}

@Composable
private fun rememberDotContraction(status: TurnStatus): Animatable<Float, *> {
    val contraction = remember { Animatable(if (status == TurnStatus.FINISHED) DOT_HANDOFF else 0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(status) {
        if (status == TurnStatus.FINISHED && contraction.value < DOT_HANDOFF) {
            delay(FLATTEN_MILLIS)
            // Both phases use the same center-to-progress mapping, so the dot inherits the line's speed.
            val velocity = (DOT_HANDOFF - contraction.value) * MILLIS_PER_SECOND / CONTRACTION_MILLIS
            contraction.animateTo(DOT_HANDOFF, tween(CONTRACTION_MILLIS, easing = LinearEasing))
            contraction.animateTo(
                DOT_HANDOFF,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.001f,
                ),
                initialVelocity = velocity,
            )
        } else {
            contraction.animateTo(if (status == TurnStatus.FINISHED) DOT_HANDOFF else 0f, spec)
        }
    }
    return contraction
}

@Composable
private fun TurnStatus.lineColor(): Color =
    when (this) {
        // A solid primary tone a few steps nearer the surface in either theme (80 in light,
        // 40 in dark): the timer's hue, quieter than the title, without a faded look.
        TurnStatus.WORKING, TurnStatus.FINISHED -> MaterialTheme.colorScheme.inversePrimary
        TurnStatus.NEEDS_ATTENTION -> MaterialTheme.colorScheme.tertiary
        TurnStatus.ERROR -> MaterialTheme.colorScheme.error
        TurnStatus.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
    }

private val waveLength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength * 0.75f
internal val dotOvershootSpace = 20.dp

// One wavelength every five seconds: present, never busy.
private const val WAVE_PERIOD_SECONDS = 5
private const val DOT_ROUNDING_START = 0.75f
private const val DOT_HANDOFF = 0.9f
private const val CONTRACTION_MILLIS = 300
private const val MILLIS_PER_SECOND = 1000f
private const val LINGER_MILLIS = 1_000L

// Material's own amplitude-decrease tween (WavyProgressIndicator's DecreasingAmplitudeAnimationSpec).
private const val FLATTEN_MILLIS = 500L
