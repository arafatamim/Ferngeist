package com.tamimarafat.ferngeist.feature.chat.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.feature.chat.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** "Today" and "Yesterday" in the device's words, otherwise the weekday and date. */
@Composable
internal fun dayLabel(
    dayMillis: Long,
    nowMillis: Long,
): String {
    val context = LocalContext.current
    val daysAgo = localDay(nowMillis).toEpochDay() - localDay(dayMillis).toEpochDay()
    return if (daysAgo in 0..1) {
        DateUtils.getRelativeTimeSpanString(dayMillis, nowMillis, DateUtils.DAY_IN_MILLIS).toString()
    } else {
        // Without a year flag DateUtils adds the year only when it is not the current one.
        DateUtils.formatDateTime(
            context,
            dayMillis,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
        )
    }
}

internal fun localDay(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
internal fun compactCommitDate(
    nowMillis: Long,
    thenMillis: Long,
): String {
    val context = LocalContext.current
    return when (val age = relativeAge(nowMillis, thenMillis)) {
        RelativeAge.Now -> stringResource(R.string.chat_git_date_now)
        is RelativeAge.Minutes -> stringResource(R.string.chat_git_date_minutes, age.count)
        is RelativeAge.Hours -> stringResource(R.string.chat_git_date_hours, age.count)
        // The date column is narrow, so a weekday out-reads "3d" here.
        is RelativeAge.Days ->
            DateUtils.formatDateTime(
                context,
                thenMillis,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
            )
        // Without a year flag DateUtils adds the year only when it is not the current one.
        RelativeAge.Older -> DateUtils.formatDateTime(context, thenMillis, DateUtils.FORMAT_ABBREV_MONTH)
    }
}
