package com.tamimarafat.ferngeist.core.common.ui.time

import android.text.format.DateUtils

/**
 * The one bucketing of a timestamp's age for compact labels: chat cards and git history both read it, with surface-specific rendering.
 */
sealed interface RelativeAge {
    data object Now : RelativeAge

    data class Minutes(
        val count: Int,
    ) : RelativeAge

    data class Hours(
        val count: Int,
    ) : RelativeAge

    data class Days(
        val count: Int,
    ) : RelativeAge

    /** A week or more: a date reads better than an age. */
    data object Older : RelativeAge
}

fun relativeAge(
    nowMillis: Long,
    thenMillis: Long,
): RelativeAge {
    // Future timestamps (clock skew between gateway and phone) read as "now".
    val elapsed = (nowMillis - thenMillis).coerceAtLeast(0)
    return when {
        elapsed < DateUtils.MINUTE_IN_MILLIS -> RelativeAge.Now
        elapsed < DateUtils.HOUR_IN_MILLIS -> RelativeAge.Minutes((elapsed / DateUtils.MINUTE_IN_MILLIS).toInt())
        elapsed < DateUtils.DAY_IN_MILLIS -> RelativeAge.Hours((elapsed / DateUtils.HOUR_IN_MILLIS).toInt())
        elapsed < DateUtils.WEEK_IN_MILLIS -> RelativeAge.Days((elapsed / DateUtils.DAY_IN_MILLIS).toInt())
        else -> RelativeAge.Older
    }
}
