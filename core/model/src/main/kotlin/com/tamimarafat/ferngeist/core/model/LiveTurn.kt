package com.tamimarafat.ferngeist.core.model

import com.agentclientprotocol.model.PlanEntry

/**
 * A chat with a known turn state: what the background
 * live-update notification needs and nothing more.
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
)
