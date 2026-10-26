package com.tamimarafat.ferngeist.feature.sessionlist.ui.date

import com.tamimarafat.ferngeist.core.model.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar buckets retain server/session identity, with unknown activity last. */
internal fun datedSessions(
    sessions: List<SessionSummary>,
    zone: ZoneId,
): Map<LocalDate?, List<SessionSummary>> =
    sessions
        .sortedWith(
            compareByDescending<SessionSummary> { it.updatedAt ?: Long.MIN_VALUE }
                .thenBy { it.serverId }
                .thenBy { it.id },
        ).groupBy { it.updatedAt?.let { timestamp -> Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() } }
