package com.tamimarafat.ferngeist.feature.sessionlist.project

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.ui.date.datedSessions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProjectDateTest {
    @Test
    fun `local midnight separates adjacent chats and unknown activity stays last`() {
        val before =
            SessionSummary(
                id = "same",
                serverId = "a",
                updatedAt = Instant.parse("2026-10-23T17:59:59Z").toEpochMilli(),
            )
        val after =
            SessionSummary(
                id = "same",
                serverId = "b",
                updatedAt = Instant.parse("2026-10-23T18:00:00Z").toEpochMilli(),
            )
        val unknown = SessionSummary(id = "unknown")
        val groups = datedSessions(listOf(unknown, before, after), ZoneId.of("Asia/Dhaka"))
        assertEquals(listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 23), null), groups.keys.toList())
        assertEquals(listOf(after, before, unknown), groups.values.flatten())
    }
}
