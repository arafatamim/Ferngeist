package com.tamimarafat.ferngeist.service

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryStatus
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.LiveTurn
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.chatIdFor
import com.tamimarafat.ferngeist.core.model.worktreeBranchOf
import kotlin.time.ComparableTimeMark

/**
 * What the live-update notification says for the turns running right now. Pure: names,
 * titles and copy are resolved by [LiveUpdateNotifier], so every selection rule below is
 * testable without Android.
 */
internal sealed interface LiveUpdateContent {
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

    /** Several agents working at once, oldest first. */
    data class Aggregate(
        val lines: List<Line>,
    ) : LiveUpdateContent {
        data class Line(
            val turn: TurnText,
            val elapsedMs: Long,
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
    dismissedTurns: Set<LiveTurnKey>,
): LiveUpdateContent? {
    val shown = turns.filter { it.status == TurnStatus.WORKING && !dismissedTurns.dismisses(turnKey(it)) }
    if (shown.isEmpty()) return null
    if (shown.size == 1) return singleTurnContent(shown.single(), agentNames, sessionTitles, nowMs)
    val lines =
        shown
            .sortedWith(
                compareBy<LiveTurn> { it.startedAtEpochMs ?: nowMs }
                    .thenBy { it.presence.sessionId },
            ).map { turn ->
                LiveUpdateContent.Aggregate.Line(
                    turn = turn.labels(agentNames, sessionTitles),
                    elapsedMs = (nowMs - (turn.startedAtEpochMs ?: nowMs)).coerceAtLeast(0),
                )
            }
    return LiveUpdateContent.Aggregate(lines)
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
internal fun titleKey(presence: ChatPresence): String = chatIdFor(presence.serverId, presence.sessionId)

/** Identity of one turn, so a dismissal holds until this turn ends and the next one starts. */
internal data class LiveTurnKey(
    val chatId: String,
    val startMark: ComparableTimeMark?,
)

internal fun turnKey(turn: LiveTurn): LiveTurnKey = LiveTurnKey(titleKey(turn.presence), turn.startMark)

internal fun MutableSet<LiveTurnKey>.retainActiveDismissals(activeKeys: Set<LiveTurnKey>) {
    val activeChatIds = activeKeys.mapTo(mutableSetOf()) { it.chatId }
    removeAll { key ->
        if (key.startMark == null) key.chatId !in activeChatIds else key !in activeKeys
    }
}

internal fun Set<LiveTurnKey>.dismisses(turn: LiveTurnKey): Boolean =
    turn in this || turn.copy(startMark = null) in this
