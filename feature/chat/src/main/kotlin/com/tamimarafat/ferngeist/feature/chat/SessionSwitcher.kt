package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlin.math.abs

/** A server with at least one live session, as shown in the switcher. */
data class SwitcherServer(
    val id: String,
    val name: String,
)

/** One live session row in the switcher sheet. Null title renders as "Untitled" in UI. */
data class SwitcherSession(
    val serverId: String,
    val sessionId: String,
    val title: String?,
    val cwd: String?,
    val updatedAt: Long?,
    val isGenerating: Boolean,
    /** True for the session the sheet was opened from: shown muted, never clickable. */
    val isCurrent: Boolean,
)

/** Live sessions of one server, newest first. */
data class SwitcherGroup(
    val serverId: String,
    val serverName: String,
    val sessions: List<SwitcherSession>,
)

/** Full switcher model: groups plus the bubble count. */
data class SwitcherUiState(
    val groups: List<SwitcherGroup> = emptyList(),
    /** All live sessions including the current one: the number the bubble shows. */
    val liveTotalCount: Int = 0,
    /** True when at least one switchable (non-current) row exists: gates bubble visibility. */
    val hasOthers: Boolean = false,
)

/**
 * Finger direction a drag commit continues through navigation, carried as the
 * `slide` route arg. Null (sheet taps, deep links) keeps the default
 * transition.
 */
enum class SlideDirection(
    val routeValue: String,
) {
    LEFT("left"),
    RIGHT("right"),
}

/**
 * Opens another chat. [slideDirection] is non-null for a drag commit (so the
 * navigation can continue the finger's direction) and null for everything
 * else (sheet taps, deep links), which keeps the default transition.
 */
typealias OnSwitchSession = (
    session: SwitcherSession,
    slideDirection: SlideDirection?,
) -> Unit

/**
 * Sessions flanking the current one in stable recency order, wrapping around
 * both ends: swipe left goes to [next], swipe right to [previous]. This
 * deliberately ignores the sheet's display order — pinning the current row
 * first re-anchors the list on every screen, so display order would ping-pong
 * between the two most recent sessions and never reach a third. Recency
 * (`updatedAt` descending, nulls last, id tiebreaks) is identical on every
 * screen, so the cycle walks all live sessions. Both null when fewer than
 * two live sessions exist or the current session is absent.
 */
fun switcherNeighbors(groups: List<SwitcherGroup>): Pair<SwitcherSession?, SwitcherSession?> {
    val ordered =
        groups
            .flatMap { it.sessions }
            .sortedWith(
                compareByDescending<SwitcherSession> { it.updatedAt ?: Long.MIN_VALUE }
                    .thenBy { it.serverId }
                    .thenBy { it.sessionId },
            )
    if (ordered.size < 2) return null to null
    val current = ordered.indexOfFirst { it.isCurrent }
    if (current == -1) return null to null
    val previous = ordered[(current - 1 + ordered.size) % ordered.size]
    val next = ordered[(current + 1) % ordered.size]
    return previous to next
}

/**
 * Which session a finished drag commits to, or null to spring back. The
 * travel picks the direction — always. A fast release wiggle in the opposite
 * direction must never overrule an established drag, so velocity only ever
 * boosts a commit when it agrees with the travel.
 */
fun resolveSwipeTarget(
    totalPx: Float,
    velocityPxPerSec: Float,
    commitThresholdPx: Float,
    flingThresholdPxPerSec: Float,
    previous: SwitcherSession?,
    next: SwitcherSession?,
): SwitcherSession? {
    val direction = if (totalPx != 0f) totalPx else velocityPxPerSec
    if (direction == 0f) return null
    val boosted =
        abs(velocityPxPerSec) > flingThresholdPxPerSec &&
            (velocityPxPerSec < 0) == (direction < 0)
    val committed = abs(totalPx) > commitThresholdPx || boosted
    if (!committed) return null
    return if (direction < 0) next else previous
}

/**
 * Derives the switcher model from cached data. Pure: no flows, no hub.
 *
 * Keeps every live session including the current one (flagged
 * [SwitcherSession.isCurrent]), orders rows by `updatedAt` descending (nulls
 * last, id tiebreak), orders groups by their newest row descending, drops
 * empty groups and unknown servers.
 */
fun deriveSwitcherGroups(
    servers: List<SwitcherServer>,
    sessionsByServer: Map<String, List<SessionSummary>>,
    liveIdsByServer: Map<String, Set<String>>,
    isGenerating: (serverId: String, sessionId: String) -> Boolean,
    currentServerId: String,
    currentSessionId: String,
): List<SwitcherGroup> {
    val unsorted =
        servers.mapNotNull { server ->
            val live = liveIdsByServer[server.id].orEmpty()
            if (live.isEmpty()) return@mapNotNull null
            val candidates = sessionsByServer[server.id].orEmpty()
            val liveRows = candidates.filter { it.id in live }
            val current = currentServerId to currentSessionId
            val orderedRows = liveRows.sortedByUpdatedAtDesc()
            val mapped =
                orderedRows.map { summary ->
                    SwitcherSession(
                        serverId = server.id,
                        sessionId = summary.id,
                        title = summary.title,
                        cwd = summary.cwd,
                        updatedAt = summary.updatedAt,
                        isGenerating = isGenerating(server.id, summary.id),
                        isCurrent = server.id to summary.id == current,
                    )
                }
            val rows = mapped.sortedByCurrentFirst()
            if (rows.isEmpty()) null else SwitcherGroup(server.id, server.name, rows)
        }
    return unsorted.sortedByCurrentGroupFirst()
}

/** Current row anchors its group: pinned first, remaining rows keep recency order. */
private fun List<SwitcherSession>.sortedByCurrentFirst(): List<SwitcherSession> =
    partition { it.isCurrent }.let { (current, rest) -> current + rest }

/** Current session's group anchors the sheet, remaining groups keep recency order. */
private fun List<SwitcherGroup>.sortedByCurrentGroupFirst(): List<SwitcherGroup> {
    val (current, rest) = partition { group -> group.sessions.any { it.isCurrent } }
    return current + rest.sortedByDescendingActivity()
}

private fun List<SessionSummary>.sortedByUpdatedAtDesc(): List<SessionSummary> =
    sortedWith(
        compareByDescending<SessionSummary> { it.updatedAt ?: Long.MIN_VALUE }
            .thenBy { it.id },
    )

private fun List<SwitcherGroup>.sortedByDescendingActivity(): List<SwitcherGroup> =
    sortedByDescending { group ->
        group.sessions.maxOfOrNull { it.updatedAt ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
    }
