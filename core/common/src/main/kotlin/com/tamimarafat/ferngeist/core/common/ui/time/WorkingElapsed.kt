package com.tamimarafat.ferngeist.core.common.ui.time

import android.icu.text.MeasureFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.delay
import kotlin.time.ComparableTimeMark

private const val TICK_MILLIS = 1000L

/** A running turn's age as a live "3m 10s"; null when the turn is not working or under a second old. */
@Composable
fun rememberWorkingElapsed(status: TurnActivity): String? {
    val age = rememberTurnAge(status.since.takeIf { status.status == TurnStatus.WORKING })
    return age?.let(::elapsedOf)?.let { formatElapsed(it, MeasureFormat.FormatWidth.NARROW) }
}

/** An unread finish as "3m ago", or "Just now" under a minute; null once read, expired, or not finished. */
@Composable
fun rememberFinishedAgo(status: TurnActivity): String? {
    val age = rememberTurnAge(status.since.takeIf { status.status == TurnStatus.FINISHED }) ?: return null
    val elapsed =
        elapsedOf(age)?.takeIf { it.hours > 0 || it.minutes > 0 }
            ?: return stringResource(R.string.common_just_now)
    // Only the largest unit: seconds beside minutes would make a finished chat tick like a running one.
    val coarse = if (elapsed.hours > 0) Elapsed(elapsed.hours, 0, 0) else Elapsed(0, elapsed.minutes, 0)
    return stringResource(R.string.common_status_ago, formatElapsed(coarse, MeasureFormat.FormatWidth.NARROW))
}

/** Only composed, started surfaces tick; a resume samples the original mark immediately. */
@Composable
private fun rememberTurnAge(since: ComparableTimeMark?): Long? {
    if (since == null) return null
    val owner = LocalLifecycleOwner.current
    var age by remember(since) { mutableLongStateOf(since.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)) }
    LaunchedEffect(since, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                age = since.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
                delay(TICK_MILLIS - age % TICK_MILLIS)
            }
        }
    }
    return age
}
