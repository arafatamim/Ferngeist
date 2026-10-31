package com.tamimarafat.ferngeist.feature.chat.ui

import android.icu.text.MeasureFormat
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tamimarafat.ferngeist.core.common.ui.time.elapsedOf
import com.tamimarafat.ferngeist.core.common.ui.time.formatElapsed
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

internal const val MS_PER_SECOND = 1000L

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
