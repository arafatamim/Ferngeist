package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.WorktreeRef
import com.tamimarafat.ferngeist.core.model.cwdKey
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.core.model.projectRootOf
import com.tamimarafat.ferngeist.core.model.worktreeOf
import kotlin.math.abs

/** A server with at least one live session, as shown in the switcher. */
data class SwitcherServer(
    val id: String,
    val name: String,
    /** The gateway's worktrees, which name a worktree row's project; empty off a gateway. */
    val worktrees: List<WorktreeRef> = emptyList(),
    /**
     * Identity its sessions group projects under: the paired gateway for gateway agents, so
     * every agent on one gateway shares a project group; the server itself for manual agents.
     */
    val sourceId: String = id,
    /** The agent's logo; null when it has none. */
    val iconUrl: String? = null,
)

/** One live session row in the switcher sheet. Null title renders as "Untitled" in UI. */
data class SwitcherSession(
    val serverId: String,
    val sessionId: String,
    val title: String?,
    val cwd: String?,
    val updatedAt: Long?,
    val turnStatus: TurnStatus,
    /** True for the session the sheet was opened from: tinted, never clickable. */
    val isCurrent: Boolean,
    /** The gateway worktree the chat runs in, when the gateway or the path convention names one. */
    val worktree: WorktreeRef? = null,
    /** The agent the session belongs to, shown on its row. */
    val agentName: String = serverId,
    /** The agent's logo, when its gateway published one. */
    val agentIconUrl: String? = null,
)

/** Live sessions of one project, newest first. */
data class SwitcherGroup(
    /** Stable project identity: the owning gateway (or manual server) plus the repo root. */
    val projectKey: String,
    /** The project's folder name; null when its sessions carry no cwd. */
    val projectName: String?,
    val sessions: List<SwitcherSession>,
)

/** The session the switcher was opened from; its row is the one flagged [SwitcherSession.isCurrent]. */
data class SwitcherCurrent(
    val serverId: String,
    val sessionId: String,
)

/** Distances a drag must cover, or fling past, to commit to a neighbouring session. */
data class SwipeThresholds(
    val commitPx: Float,
    val flingPxPerSec: Float,
)

/** Full switcher model: groups plus the bubble count. */
data class SwitcherUiState(
    val groups: List<SwitcherGroup> = emptyList(),
    /** All live sessions including the current one: the number the bubble shows. */
    val liveTotalCount: Int = 0,
    /** True when at least one switchable (non-current) row exists: gates bubble visibility. */
    val hasOthers: Boolean = false,
)

val SwitcherUiState.badgeStatus: TurnStatus?
    get() {
        val statuses =
            groups
                .asSequence()
                .flatMap { it.sessions.asSequence() }
                .filterNot { it.isCurrent }
                .map { it.turnStatus }
                .toSet()
        return listOf(TurnStatus.NEEDS_ATTENTION, TurnStatus.ERROR, TurnStatus.FINISHED)
            .firstOrNull { it in statuses }
    }

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
 * Where a drag commit let go: its [direction], and how far the chat had already
 * travelled, as a fraction of the width ([travelled], 0..1). The transition
 * starts from there, so the outgoing chat and the incoming one move as one sheet
 * instead of the incoming one jumping in from the far edge.
 */
data class SwitchSlide(
    val direction: SlideDirection,
    val travelled: Float,
)

/**
 * Opens another chat. [slide] is non-null for a drag commit (so the navigation
 * can continue the finger's motion) and null for everything else (sheet taps,
 * deep links), which keeps the default transition.
 */
typealias OnSwitchSession = (
    session: SwitcherSession,
    slide: SwitchSlide?,
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
    thresholds: SwipeThresholds,
    previous: SwitcherSession?,
    next: SwitcherSession?,
): SwitcherSession? {
    val direction = if (totalPx != 0f) totalPx else velocityPxPerSec
    if (direction == 0f) return null
    val boosted =
        abs(velocityPxPerSec) > thresholds.flingPxPerSec &&
            (velocityPxPerSec < 0) == (direction < 0)
    val committed = abs(totalPx) > thresholds.commitPx || boosted
    if (!committed) return null
    return if (direction < 0) next else previous
}

/**
 * Derives the switcher model from cached data. Pure: no flows, no hub.
 *
 * Keeps every live session including the current one (flagged
 * [SwitcherSession.isCurrent]) and groups rows by project: the owning
 * gateway's [SwitcherServer.sourceId] plus the normalized repo root, so agents
 * sharing a gateway and repo share a group while separate gateways (and manual
 * servers) never merge. Sessions without a cwd fall into one unknown project
 * per server. Rows order by `updatedAt` descending (nulls last, server and id
 * tiebreaks) with the current row pinned first; the current row's group is
 * pinned first and the rest follow by newest row. Empty groups and unknown
 * servers are dropped.
 */
fun deriveSwitcherGroups(
    servers: List<SwitcherServer>,
    sessionsByServer: Map<String, List<SessionSummary>>,
    liveIdsByServer: Map<String, Set<String>>,
    turnStatus: (serverId: String, sessionId: String) -> TurnStatus,
    current: SwitcherCurrent,
): List<SwitcherGroup> {
    val rowsByProject = linkedMapOf<String, MutableList<SwitcherSession>>()
    val namesByProject = linkedMapOf<String, String?>()
    for (server in servers) {
        val live = liveIdsByServer[server.id].orEmpty()
        if (live.isEmpty()) continue
        for (summary in sessionsByServer[server.id].orEmpty()) {
            if (summary.id !in live) continue
            val cwd = summary.cwd?.takeIf { it.isNotBlank() }
            val root = cwd?.let { projectRootOf(it, server.worktrees) }
            val key =
                if (root == null) {
                    "owner:${server.id}"
                } else {
                    "project:${server.sourceId}|${cwdKey(root)}"
                }
            namesByProject[key] = root?.let(::projectName)
            rowsByProject.getOrPut(key) { mutableListOf() } +=
                SwitcherSession(
                    serverId = server.id,
                    sessionId = summary.id,
                    title = summary.title,
                    cwd = summary.cwd,
                    updatedAt = summary.updatedAt,
                    turnStatus = turnStatus(server.id, summary.id),
                    isCurrent = server.id == current.serverId && summary.id == current.sessionId,
                    worktree = cwd?.let { worktreeOf(it, server.worktrees) },
                    agentName = server.name,
                    agentIconUrl = server.iconUrl,
                )
        }
    }
    return rowsByProject
        .map { (key, rows) ->
            SwitcherGroup(
                projectKey = key,
                projectName = namesByProject[key],
                sessions = rows.sortedWith(recencyOrder).sortedByCurrentFirst(),
            )
        }.sortedByCurrentGroupFirst()
}

private val recencyOrder =
    compareByDescending<SwitcherSession> { it.updatedAt ?: Long.MIN_VALUE }
        .thenBy { it.serverId }
        .thenBy { it.sessionId }

/** Current row anchors its group: pinned first, remaining rows keep recency order. */
private fun List<SwitcherSession>.sortedByCurrentFirst(): List<SwitcherSession> =
    partition { it.isCurrent }.let { (current, rest) -> current + rest }

/** Current session's group anchors the sheet, remaining groups keep recency order. */
private fun List<SwitcherGroup>.sortedByCurrentGroupFirst(): List<SwitcherGroup> {
    val (current, rest) = partition { group -> group.sessions.any { it.isCurrent } }
    return current + rest.sortedByDescendingActivity()
}

private fun List<SwitcherGroup>.sortedByDescendingActivity(): List<SwitcherGroup> =
    sortedByDescending { group ->
        group.sessions.maxOfOrNull { it.updatedAt ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
    }
