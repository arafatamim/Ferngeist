package com.tamimarafat.ferngeist.core.common.ui.time

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tamimarafat.ferngeist.core.common.R
import java.text.SimpleDateFormat
import java.util.Date

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

/** Compact age for chat cards; after a week a localized date is more useful. */
@Composable
fun compactRelativeTime(
    updatedAt: Long,
    now: Long,
): String =
    when (val age = relativeAge(now, updatedAt)) {
        RelativeAge.Now -> stringResource(R.string.chat_git_date_now)
        is RelativeAge.Minutes -> stringResource(R.string.chat_git_date_minutes, age.count)
        is RelativeAge.Hours -> stringResource(R.string.chat_git_date_hours, age.count)
        is RelativeAge.Days -> stringResource(R.string.chat_git_date_days, age.count)
        RelativeAge.Older -> {
            val locale = LocalContext.current.resources.configuration.locales[0]
            val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
            SimpleDateFormat(pattern, locale).format(Date(updatedAt))
        }
    }
