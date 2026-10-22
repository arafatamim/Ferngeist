package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.core.model.normalizeCwd

/**
 * Gateway-managed worktrees live at `<repo>/.worktrees/<branch>`, so this is the join point.
 * Matched on `/`-normalized paths, since a Windows gateway reports `\`-separated ones.
 */
internal const val WORKTREES_SEGMENT = "/.worktrees/"

/**
 * Narrows [sessions] to the ones inside [cwdFilter], preserving order.
 *
 * The list is filtered locally as well as by the hub's listing: a gateway can
 * only filter by re-asking the agent, so while the agent is unreachable a cwd
 * change would leave the shown list untouched. Comparison ignores surrounding
 * whitespace, trailing separators and letter case, so a hand-typed directory
 * still matches the path the gateway reported for its own sessions.
 *
 * A chat opened in a gateway worktree has the worktree directory as its cwd, which never
 * equals the repo the user filters by, so a path under `<repo>/.worktrees/` also counts as
 * being inside `<repo>`. Without that, a repo's worktree chats would drop out of its group
 * the moment the filter is set.
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
        val sessionCwd = session.cwd?.let(::normalizeCwd) ?: return@filter false
        sessionCwd.equals(filter, ignoreCase = true) ||
            worktreeRepoOf(sessionCwd)?.equals(filter, ignoreCase = true) == true
    }
}

/**
 * The repo a gateway worktree path belongs to, i.e. `/repo/.worktrees/feat-x` -> `/repo`.
 * Null when [cwd] is not inside a `.worktrees` folder; a relative path can never be, so a
 * match at index 0 is rejected rather than yielding an empty repo.
 */
internal fun worktreeRepoOf(cwd: String): String? {
    val marker = cwd.replace('\\', '/').indexOf(WORKTREES_SEGMENT, ignoreCase = true)
    return if (marker > 0) cwd.take(marker) else null
}

/**
 * Ids of the chats that run in the worktree at [path]: [sessionId] plus every chat whose cwd is
 * that directory. A null [path] (the worktree is no longer listed) narrows it to [sessionId].
 */
internal fun chatsInWorktree(
    sessions: List<SessionSummary>,
    sessionId: String?,
    path: String?,
): List<String> =
    sessions
        .filter { session ->
            session.id == sessionId || (path != null && session.cwd?.let { isSameCwd(it, path) } == true)
        }.map { it.id }
