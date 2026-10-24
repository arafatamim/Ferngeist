package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import java.time.Duration
import java.time.temporal.ChronoUnit

private val ageUnits = listOf(ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.WEEKS, ChronoUnit.DAYS, ChronoUnit.HOURS)

/**
 * A worktree's age as its largest whole unit, for a `2d`-style label; under an hour counts in
 * minutes, at least one. Months and years are git's 30 and 365 days, not the calendar's.
 */
internal fun worktreeAge(age: Duration): Pair<Long, ChronoUnit> {
    val days = age.toDays()
    for (unit in ageUnits) {
        val count =
            when (unit) {
                ChronoUnit.YEARS -> days / 365
                ChronoUnit.MONTHS -> days / 30
                ChronoUnit.WEEKS -> days / 7
                ChronoUnit.DAYS -> days
                else -> age.toHours()
            }
        if (count > 0) return count to unit
    }
    return age.toMinutes().coerceAtLeast(1) to ChronoUnit.MINUTES
}
