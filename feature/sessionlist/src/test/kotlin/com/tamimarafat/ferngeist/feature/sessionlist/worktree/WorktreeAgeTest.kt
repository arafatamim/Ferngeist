package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.temporal.ChronoUnit

class WorktreeAgeTest {
    @Test
    fun worktreeAge_takesTheLargestWholeUnit() {
        assertEquals(1L to ChronoUnit.MINUTES, worktreeAge(Duration.ofSeconds(10)))
        assertEquals(3L to ChronoUnit.HOURS, worktreeAge(Duration.ofMinutes(200)))
        assertEquals(2L to ChronoUnit.DAYS, worktreeAge(Duration.ofHours(50)))
        assertEquals(1L to ChronoUnit.WEEKS, worktreeAge(Duration.ofDays(13)))
        assertEquals(2L to ChronoUnit.MONTHS, worktreeAge(Duration.ofDays(64)))
        assertEquals(1L to ChronoUnit.YEARS, worktreeAge(Duration.ofDays(400)))
    }
}
