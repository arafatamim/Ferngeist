package com.tamimarafat.ferngeist.feature.chat.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.feature.chat.SegmentBlock
import kotlinx.coroutines.delay

// How long a live tool group has run, for its header; gone once the group ends.
// Times are elapsedRealtime stamps from the reducer; hydrated history has none and shows no time.

/** The first segment a block holds, whose arrival is when the block began. */
internal fun SegmentBlock.firstSegment(): AssistantSegment =
    when (this) {
        is SegmentBlock.Run -> segments.first()
        is SegmentBlock.Single -> segment
        is SegmentBlock.Group -> items.first().firstSegment()
    }

/** A duration as it reads: seconds under a minute, minutes and seconds, then hours and minutes. */
internal data class Elapsed(
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
)

/** Null under a second, which is not worth a label. */
internal fun elapsedOf(ms: Long): Elapsed? {
    val total = ms / MS_PER_SECOND
    if (total < 1) return null
    val hours = total / SECONDS_PER_HOUR
    val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    // Past an hour, seconds are noise.
    val seconds = if (hours > 0) 0L else total % SECONDS_PER_MINUTE
    return Elapsed(hours, minutes, seconds)
}

internal const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

/**
 * Milliseconds from [startMs] to now. Ticks on the span's own whole seconds, so the label turns over
 * exactly as a second passes, and stops when it leaves composition.
 */
@Composable
private fun rememberElapsedMs(startMs: Long?): Long? {
    if (startMs == null) return null
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(MS_PER_SECOND - (now - startMs).mod(MS_PER_SECOND))
        }
    }
    return now - startMs
}

@Composable
internal fun formatElapsed(
    elapsed: Elapsed,
    width: MeasureFormat.FormatWidth,
): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale, width) { MeasureFormat.getInstance(locale, width) }
    val measures =
        listOfNotNull(
            elapsed.hours.takeIf { it > 0 }?.let { Measure(it, MeasureUnit.HOUR) },
            elapsed.minutes.takeIf { it > 0 }?.let { Measure(it, MeasureUnit.MINUTE) },
            elapsed.seconds.takeIf { it > 0 }?.let { Measure(it, MeasureUnit.SECOND) },
        )
    // At most three units, so each is passed directly rather than through a spread of an array copy.
    return when (measures.size) {
        0 -> format.formatMeasures()
        1 -> format.formatMeasures(measures[0])
        2 -> format.formatMeasures(measures[0], measures[1])
        else -> format.formatMeasures(measures[0], measures[1], measures[2])
    }
}

/** The header's "1m 20s", and the spoken form TalkBack reads instead. */
internal class ElapsedLabel(
    val text: String,
    val description: String,
)

@Composable
internal fun rememberElapsedLabel(
    startedAtMs: Long?,
    live: Boolean,
): ElapsedLabel? {
    if (!live) return null
    val elapsed = rememberElapsedMs(startedAtMs)?.let(::elapsedOf) ?: return null
    return ElapsedLabel(
        text = formatElapsed(elapsed, MeasureFormat.FormatWidth.NARROW),
        description = formatElapsed(elapsed, MeasureFormat.FormatWidth.WIDE),
    )
}
