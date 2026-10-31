package com.tamimarafat.ferngeist.core.common.ui.time

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** A duration as it reads: seconds under a minute, minutes and seconds, then hours and minutes. */
data class Elapsed(
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
)

/** Null under a second, which is not worth a label. */
fun elapsedOf(ms: Long): Elapsed? {
    val total = ms / MS_PER_SECOND
    if (total < 1) return null
    val hours = total / SECONDS_PER_HOUR
    val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    // Past an hour, seconds are noise.
    val seconds = if (hours > 0) 0L else total % SECONDS_PER_MINUTE
    return Elapsed(hours, minutes, seconds)
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

@Composable
fun formatElapsed(
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
