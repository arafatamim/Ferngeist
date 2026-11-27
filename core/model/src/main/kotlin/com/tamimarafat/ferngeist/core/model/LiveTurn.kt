package com.tamimarafat.ferngeist.core.model

import com.agentclientprotocol.model.PlanEntry
import kotlin.time.ComparableTimeMark

/**
 * An attached chat's turn state for live activity and drawer notifications.
 *
 * The hub derives it from each chat's live session snapshot; agent names, session titles and
 * the notification copy belong to the app.
 */
data class LiveTurn(
    /** Identity + chrome for the notification's tap target. */
    val presence: ChatPresence,
    val status: TurnStatus,
    /** Wall-clock turn start, for the notification's chronometer; null when the runtime carries none. */
    val startedAtEpochMs: Long? = null,
    /** The live turn's latest plan, in agent order; empty when the agent published none. */
    val planEntries: List<PlanEntry> = emptyList(),
    /** What the agent is doing now (its latest tool-call title), or null when it is not saying. */
    val activity: String? = null,
    val title: String? = null,
    val startMark: ComparableTimeMark? = null,
    /** Identifies an outcome observed since this chat's snapshot was attached. */
    val outcomeId: OutcomeId? = null,
) {
    data class OutcomeId(
        val attachmentGeneration: Long,
        val sequence: Long,
    )
}
