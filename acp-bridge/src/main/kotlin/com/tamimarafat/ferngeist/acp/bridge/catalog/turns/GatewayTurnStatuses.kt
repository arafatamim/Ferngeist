package com.tamimarafat.ferngeist.acp.bridge.catalog.turns

import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurn
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnsSnapshot
import java.time.Instant
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Status per ACP session id, by agent id: one gateway's answer, ready to join onto its bindings. */
internal typealias AgentTurnStatuses = Map<String, Map<String, TurnActivity>>

internal const val RUNNING = "running"
internal const val SUCCEEDED = "succeeded"
internal const val FAILED = "failed"

/** One status per ACP chat, however many gateway sessions ran a turn for it. */
internal fun GatewayTurnsSnapshot.turnStatuses(
    acknowledged: Set<GatewayTurn> = emptySet(),
    expiredOutcomes: Set<GatewayTurn> = emptySet(),
    receivedAt: ComparableTimeMark = TimeSource.Monotonic.markNow(),
): AgentTurnStatuses =
    turns
        .groupBy { it.agentId }
        .mapValues { (_, agentTurns) ->
            agentTurns.groupBy { it.sessionId }.mapValues { (_, entries) ->
                entries.chatStatus(acknowledged, expiredOutcomes, observedAt, receivedAt)
            }
        }

private const val FINISHED_WINDOW_MILLIS = 600_000L
private const val FAILED_WINDOW_MILLIS = 300_000L

/** Snapshot-relative age avoids trusting the device wall clock for a gateway's completion time. */
internal fun GatewayTurn.expiryDelay(observedAt: String): Long {
    val changed = changedAt.instant()
    val observed = observedAt.instant()
    val age =
        if (changed == null || observed == null || changed.isAfter(observed)) {
            null
        } else {
            runCatching {
                java.time.Duration
                    .between(changed, observed)
                    .toMillis()
            }.getOrNull()
        }
    val window = if (state == FAILED) FAILED_WINDOW_MILLIS else FINISHED_WINDOW_MILLIS
    return age?.let { (window - it).coerceAtLeast(0) } ?: 0L
}

/**
 * A prompt in flight outranks every stored outcome, and simultaneous running entries count
 * together, so a pending request on any of them needs attention. Otherwise the latest started
 * outcome is the current one: start times compare at the gateway's full nanosecond precision,
 * the change time and the gateway session id only break exact ties, and an entry whose start
 * time cannot be read claims nothing.
 */
private fun List<GatewayTurn>.chatStatus(
    acknowledged: Set<GatewayTurn>,
    expiredOutcomes: Set<GatewayTurn>,
    observedAt: String,
    receivedAt: ComparableTimeMark,
): TurnActivity {
    val running = filter { it.state == RUNNING }
    if (running.isNotEmpty()) {
        val status = if (running.any { it.attention.isNotEmpty() }) TurnStatus.NEEDS_ATTENTION else TurnStatus.WORKING
        val starts = running.map { it.startedAt.ageAt(observedAt)?.let { age -> receivedAt - age.milliseconds } }
        return TurnActivity(status, if (starts.any { it == null }) null else starts.filterNotNull().minOrNull())
    }
    val latest =
        maxWithOrNull(
            compareBy<GatewayTurn>(
                { it.startedAt.instant() ?: Instant.MIN },
                { it.changedAt.instant() ?: Instant.MIN },
                { it.gatewaySessionId },
            ),
        ) ?: return TurnActivity()
    // Unable to order this outcome against the others, the gateway session id means nothing.
    if (latest.startedAt.instant() == null ||
        latest in acknowledged ||
        latest in expiredOutcomes
    ) {
        return TurnActivity()
    }
    val status = latest.status()
    val since =
        if (status == TurnStatus.FINISHED) {
            latest.changedAt.ageAt(observedAt)?.let { receivedAt - it.milliseconds }
        } else {
            null
        }
    return TurnActivity(status, since)
}

/** The gateway supplies both instants; only elapsed local time advances the result after receipt. */
private fun String?.ageAt(observedAt: String): Long? {
    val time = instant() ?: return null
    val observed = observedAt.instant() ?: return null
    return runCatching {
        java.time.Duration
            .between(time, observed)
            .toMillis()
            .coerceAtLeast(0)
    }.getOrNull()
}

/** Cancelled, unknown and anything a newer gateway adds carry no success claim. */
private fun GatewayTurn.status(): TurnStatus =
    when (state) {
        RUNNING -> TurnStatus.WORKING
        SUCCEEDED -> TurnStatus.FINISHED
        FAILED -> TurnStatus.ERROR
        else -> TurnStatus.UNKNOWN
    }

/** RFC3339Nano at the precision the gateway sent; a time this client cannot read orders last. */
private fun String?.instant(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
