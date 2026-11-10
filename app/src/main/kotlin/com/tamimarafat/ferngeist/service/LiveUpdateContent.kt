package com.tamimarafat.ferngeist.service

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryStatus
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.LiveTurn
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.worktreeBranchOf

/**
 * What the live-update notification says for the turns running right now. Pure: names,
 * titles and copy are resolved by [LiveUpdateNotifier], so every selection rule below is
 * testable without Android.
 */
internal sealed interface LiveUpdateContent {
    data class Completed(
        val done: Int,
        val errors: Int,
    ) : LiveUpdateContent

    /** One agent following a plan: the "2/5" chip and its segmented bar. */
    data class PlanStep(
        val turn: TurnText,
        val current: Int,
        val total: Int,
        val step: String,
        /** Steps already completed, in segment units. */
        val completed: Int,
    ) : LiveUpdateContent

    /** One agent with no plan: the chronometer and what it is doing. */
    data class Working(
        val turn: TurnText,
        val branch: String?,
        val activity: String?,
        val startedAtEpochMs: Long,
    ) : LiveUpdateContent

    /** Several agents at once, blocked ones first. */
    data class Aggregate(
        val lines: List<Line>,
        val busyCount: Int,
        val needsYou: Boolean,
    ) : LiveUpdateContent {
        data class Line(
            val turn: TurnText,
            val elapsedMs: Long,
            val needsYou: Boolean,
        )
    }

    /** Identity and labels one turn needs to deep-link and read. */
    data class TurnText(
        val presence: ChatPresence,
        val agentName: String?,
        val sessionTitle: String?,
    )
}

/** How far a plan has got: the first in-progress step (else the first pending, else the last). */
internal data class PlanProgress(
    val current: Int,
    val total: Int,
    val step: String,
    val completed: Int,
)

internal fun planProgress(entries: List<PlanEntry>): PlanProgress? {
    if (entries.isEmpty()) return null
    val currentIndex =
        entries
            .indexOfFirst { it.status == PlanEntryStatus.IN_PROGRESS }
            .takeIf { it >= 0 }
            ?: entries.indexOfFirst { it.status == PlanEntryStatus.PENDING }.takeIf { it >= 0 }
            ?: entries.lastIndex
    return PlanProgress(
        current = currentIndex + 1,
        total = entries.size,
        step = entries[currentIndex].content,
        completed = entries.count { it.status == PlanEntryStatus.COMPLETED },
    )
}

/**
 * Picks the notification variant: a plan bar for one agent that published a plan, a chronometer
 * for one that did not, and the aggregate list for several. Null when no turn is live.
 */
internal fun liveUpdateContent(
    turns: List<LiveTurn>,
    agentNames: Map<String, String>,
    sessionTitles: Map<String, String?>,
    nowMs: Long,
): LiveUpdateContent? {
    if (turns.isEmpty()) return null
    if (turns.size == 1) return singleTurnContent(turns.single(), agentNames, sessionTitles, nowMs)
    val lines =
        turns
            .sortedWith(
                compareByDescending<LiveTurn> { it.status == TurnStatus.NEEDS_ATTENTION }
                    .thenBy { it.startedAtEpochMs ?: nowMs }
                    .thenBy { it.presence.sessionId },
            ).map { turn ->
                LiveUpdateContent.Aggregate.Line(
                    turn = turn.labels(agentNames, sessionTitles),
                    elapsedMs = (nowMs - (turn.startedAtEpochMs ?: nowMs)).coerceAtLeast(0),
                    needsYou = turn.status == TurnStatus.NEEDS_ATTENTION,
                )
            }
    return LiveUpdateContent.Aggregate(
        lines = lines,
        busyCount = turns.size,
        needsYou = turns.any { it.status == TurnStatus.NEEDS_ATTENTION },
    )
}

private fun singleTurnContent(
    turn: LiveTurn,
    agentNames: Map<String, String>,
    sessionTitles: Map<String, String?>,
    nowMs: Long,
): LiveUpdateContent {
    val text = turn.labels(agentNames, sessionTitles)
    val plan = planProgress(turn.planEntries)
    if (plan != null) {
        return LiveUpdateContent.PlanStep(
            turn = text,
            current = plan.current,
            total = plan.total,
            step = plan.step,
            completed = plan.completed,
        )
    }
    return LiveUpdateContent.Working(
        turn = text,
        branch = worktreeBranchOf(turn.presence.cwd)?.ifBlank { null },
        activity = turn.activity?.takeIf { it.isNotBlank() },
        startedAtEpochMs = turn.startedAtEpochMs ?: nowMs,
    )
}

internal fun LiveTurn.labels(
    agentNames: Map<String, String>,
    sessionTitles: Map<String, String?>,
): LiveUpdateContent.TurnText =
    LiveUpdateContent.TurnText(
        presence = presence,
        agentName = agentNames[presence.serverId]?.takeIf { it.isNotBlank() },
        sessionTitle = sessionTitles[titleKey(presence)]?.takeIf { it.isNotBlank() },
    )

/** Catalog key for one session's title: the same `serverId/sessionId` the status catalogs use. */
internal fun titleKey(presence: ChatPresence): String = "${presence.serverId}/${presence.sessionId}"

/** Identity of one turn, so a dismissal holds until this turn ends and the next one starts. */
internal fun turnKey(turn: LiveTurn): String = "${titleKey(turn.presence)}@${turn.startedAtEpochMs}"
