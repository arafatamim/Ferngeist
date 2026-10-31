package com.tamimarafat.ferngeist.core.model

import kotlin.time.ComparableTimeMark

/**
 * The one turn status every surface reads: whether a session is busy, waiting on the user,
 * or has a known terminal outcome.
 *
 * [UNKNOWN] claims nothing - no turn is known to be running and no outcome is known - so a
 * cold start, a lost connection, or hydration never presents as finished.
 */
enum class TurnStatus {
    UNKNOWN,
    FINISHED,
    WORKING,
    ERROR,
    NEEDS_ATTENTION,
}

/**
 * The status a project or agent card shows for the sessions under it: the highest-priority
 * known one, as a request awaits the user first, then a failure, then a running turn, then
 * a finished one. [TurnStatus.UNKNOWN] sessions change nothing, so a card whose sessions are
 * all unknown stays unknown rather than reading as finished.
 */
fun Iterable<TurnStatus>.aggregateTurnStatus(): TurnStatus = maxOrNull() ?: TurnStatus.UNKNOWN

/** A status and its in-memory monotonic origin: turn start while working, completion when finished. */
data class TurnActivity(
    val status: TurnStatus = TurnStatus.UNKNOWN,
    val since: ComparableTimeMark? = null,
)

/** Keep the oldest running turn or newest completion alongside the winning status. */
fun Iterable<TurnActivity>.aggregateTurnActivity(): TurnActivity {
    val status = map { it.status }.aggregateTurnStatus()
    val matching = filter { it.status == status }
    val marks = matching.mapNotNull { it.since }
    val since =
        when {
            marks.size != matching.size -> null
            status == TurnStatus.WORKING -> marks.minOrNull()
            status == TurnStatus.FINISHED -> marks.maxOrNull()
            else -> null
        }
    return TurnActivity(status, since)
}
