package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary

/**
 * Narrows [sessions] to the ones inside [cwdFilter], preserving order.
 *
 * The list is filtered locally as well as by the hub's listing: a gateway can
 * only filter by re-asking the agent, so while the agent is unreachable a cwd
 * change would leave the shown list untouched. Comparison ignores surrounding
 * whitespace, trailing separators and letter case, so a hand-typed directory
 * still matches the path the gateway reported for its own sessions.
 *
 * A null or blank [cwdFilter] means "show all sessions".
 */
fun filterSessionsByCwd(
    sessions: List<SessionSummary>,
    cwdFilter: String?,
): List<SessionSummary> {
    val filter = cwdFilter?.let(::normalizeCwd).orEmpty()
    if (filter.isEmpty()) return sessions
    return sessions.filter { session ->
        session.cwd?.let(::normalizeCwd)?.equals(filter, ignoreCase = true) == true
    }
}

private fun normalizeCwd(cwd: String): String = cwd.trim().trimEnd('\\', '/')
