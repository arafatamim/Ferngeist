package com.tamimarafat.ferngeist.core.common.ui.time

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tamimarafat.ferngeist.core.common.R

/** Age-sensitive presentation is recalculated on resume and completed refreshes, without polling. */
@Composable
fun rememberPresentationTime(refreshing: Boolean = false): Long {
    val owner = LocalLifecycleOwner.current
    var now by remember(refreshing) { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(owner, refreshing) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) now = System.currentTimeMillis()
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return now
}

@Composable
fun relativeTime(
    updatedAt: Long,
    now: Long,
): String =
    if (now - updatedAt < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.common_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
