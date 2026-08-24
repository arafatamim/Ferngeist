package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary

enum class CwdSuggestionSource { RECENT, SESSION }

data class CwdSuggestion(
    val cwd: String,
    val source: CwdSuggestionSource,
)

/**
 * Builds the filtered cwd suggestion list for the cwd picker.
 *
 * Candidates are the union of recent CWDs (MRU order preserved) and CWDs
 * observed on existing sessions (ordered by most recent [SessionSummary.updatedAt],
 * ties broken alphabetically). Recents win on duplicates. An empty or blank
 * query returns every candidate; otherwise a case-insensitive substring match
 * anywhere in the path is applied.
 */
fun buildCwdSuggestions(
    query: String,
    recentCwds: List<String>,
    sessions: List<SessionSummary>,
): List<CwdSuggestion> {
    val candidates = LinkedHashMap<String, CwdSuggestion>()
    recentCwds.forEach { cwd ->
        if (cwd.isNotBlank()) {
            candidates.putIfAbsent(cwd, CwdSuggestion(cwd, CwdSuggestionSource.RECENT))
        }
    }

    val lastUsedByCwd = mutableMapOf<String, Long>()
    for (session in sessions) {
        val cwd = session.cwd?.trim().orEmpty()
        if (cwd.isEmpty()) continue
        val updatedAt = session.updatedAt ?: Long.MIN_VALUE
        val current = lastUsedByCwd[cwd]
        if (current == null || updatedAt > current) {
            lastUsedByCwd[cwd] = updatedAt
        }
    }
    lastUsedByCwd.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .forEach { (cwd, _) ->
            candidates.putIfAbsent(cwd, CwdSuggestion(cwd, CwdSuggestionSource.SESSION))
        }

    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) {
        candidates.values.toList()
    } else {
        candidates.values.filter { it.cwd.contains(normalizedQuery, ignoreCase = true) }
    }
}
