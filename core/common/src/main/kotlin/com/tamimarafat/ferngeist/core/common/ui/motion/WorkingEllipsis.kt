package com.tamimarafat.ferngeist.core.common.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The trailing "…" of a working status ("Writing…"): three dots hop in left to right, rest level,
 * fade out together and repeat, after the M3 Expressive shape demo.
 *
 * The width of all three dots is reserved throughout, so the text beside it never shifts. Its first
 * baseline is the dots' resting line: put it after a [Text] in a [Row] with `alignByBaseline()` on
 * both. Reduced motion shows three still dots.
 */
@Composable
fun WorkingEllipsis(
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    fontSize: TextUnit = LocalTextStyle.current.fontSize,
) {
    val emPx = with(LocalDensity.current) { (if (fontSize.isUnspecified) fallbackFontSize else fontSize).toPx() }
    // A static preview renders only the first frame, which is the empty start of the loop; start
    // previews at the rest instead, so the still image shows the dots.
    val startMs = if (LocalInspectionMode.current) ELLIPSIS_REST_MS else 0f
    val clock: State<Float>? =
        if (rememberReduceMotion()) {
            null
        } else {
            // A linear clock only: the curves live in ellipsisDotFrame.
            rememberInfiniteTransition(label = "WorkingEllipsis").animateFloat(
                initialValue = startMs,
                targetValue = startMs + ELLIPSIS_PERIOD_MS,
                animationSpec = infiniteRepeatable(tween(ELLIPSIS_PERIOD_MS.toInt(), easing = LinearEasing)),
                label = "WorkingEllipsisClock",
            )
        }
    val diameter = emPx * DOT_DIAMETER_EM
    val pitch = emPx * DOT_PITCH_EM
    val lead = pitch - diameter
    val liftPx = diameter * HOP_HEIGHT_DIAMETERS
    Spacer(
        modifier
            .layout { measurable, _ ->
                val width = (lead + 2 * pitch + diameter).roundToInt()
                val height = (diameter + liftPx).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height, mapOf(FirstBaseline to height)) { placeable.place(0, 0) }
            }.drawBehind {
                // Read here, not in composition, so each frame redraws without recomposing.
                val time = clock?.value ?: ELLIPSIS_REST_MS
                repeat(DOT_COUNT) { index ->
                    val frame = ellipsisDotFrame(index, time)
                    if (frame.alpha <= 0f) return@repeat
                    val x = lead + diameter / 2 + index * pitch
                    drawCircle(
                        color = color.copy(alpha = color.alpha * frame.alpha),
                        radius = diameter / 2,
                        center =
                            Offset(
                                x = if (layoutDirection == LayoutDirection.Rtl) size.width - x else x,
                                y = size.height - diameter / 2 - frame.lift * liftPx,
                            ),
                    )
                }
            },
    )
}

/** One dot at one moment: [alpha] 0..1, [lift] 0 (on the baseline) to 1 (top of the hop). */
internal data class DotFrame(
    val alpha: Float,
    val lift: Float,
)

/**
 * Where dot [index] is [timeMs] into the loop. Each dot fades in as it hops, 280ms after the one
 * before; all three rest level, fade out together at 1160ms, and the line stays empty until 1500ms.
 */
internal fun ellipsisDotFrame(
    index: Int,
    timeMs: Float,
): DotFrame {
    val time = timeMs.mod(ELLIPSIS_PERIOD_MS)
    val local = time - index * DOT_STAGGER_MS
    if (local < 0f) return DotFrame(alpha = 0f, lift = 0f)
    val fadeIn = (local / FADE_IN_MS).coerceAtMost(1f)
    val fadeOut = 1f - ((time - FADE_OUT_START_MS) / FADE_OUT_MS).coerceIn(0f, 1f)
    return DotFrame(alpha = minOf(fadeIn, fadeOut), lift = hopLift(local / HOP_MS))
}

/** A quick lift, then a soft landing: level at the peak and again at touchdown. */
private fun hopLift(progress: Float): Float =
    when {
        progress >= 1f -> 0f
        progress < HOP_PEAK_FRACTION -> {
            val rise = 1f - progress / HOP_PEAK_FRACTION
            1f - rise * rise
        }
        else -> {
            val fall = (progress - HOP_PEAK_FRACTION) / (1f - HOP_PEAK_FRACTION)
            (1f + cos(PI.toFloat() * fall)) / 2f
        }
    }

// Half the demo's pace: at full speed it read as frantic beside a calm reply.
internal const val ELLIPSIS_PERIOD_MS = 1500f

/** A moment in the rest, when all three dots sit level and fully visible. */
internal const val ELLIPSIS_REST_MS = 1060f

private const val DOT_COUNT = 3
private const val DOT_STAGGER_MS = 280f
private const val FADE_IN_MS = 160f
private const val HOP_MS = 400f
private const val HOP_PEAK_FRACTION = 0.4f
private const val FADE_OUT_START_MS = 1160f
private const val FADE_OUT_MS = 180f
private const val DOT_DIAMETER_EM = 0.17f
private const val DOT_PITCH_EM = 0.27f
private const val HOP_HEIGHT_DIAMETERS = 0.5f
private val fallbackFontSize = 14.sp

@Preview(showBackground = true)
@Composable
private fun WorkingEllipsisPreview() {
    MaterialTheme {
        Surface {
            Row {
                Text("Writing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.alignByBaseline())
                WorkingEllipsis(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}
