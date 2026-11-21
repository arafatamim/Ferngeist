package com.tamimarafat.ferngeist.feature.sessionlist.ui.date

import com.tamimarafat.ferngeist.core.model.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Calendar buckets retain server/session identity, with unknown activity last. A chat mid-turn counts
 * as [today] and leads its bucket: the agent only stamps `updatedAt` once it records a change, so a
 * running chat would otherwise sit below one that ended minutes ago.
 */
internal fun datedSessions(
    sessions: List<SessionSummary>,
    zone: ZoneId,
    today: LocalDate,
    isLive: (SessionSummary) -> Boolean,
): Map<LocalDate?, List<SessionSummary>> =
    sessions
        .sortedWith(
            compareByDescending<SessionSummary> { isLive(it) }
                .thenByDescending { it.updatedAt ?: Long.MIN_VALUE }
                .thenBy { it.serverId }
                .thenBy { it.id },
        ).groupBy {
            if (isLive(it)) today else it.updatedAt?.let { ms -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }
        }
