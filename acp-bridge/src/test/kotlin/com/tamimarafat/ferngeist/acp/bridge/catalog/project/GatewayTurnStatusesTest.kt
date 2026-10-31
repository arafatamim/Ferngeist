package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurn
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class GatewayTurnStatusesTest {
    @Test
    fun `working age uses the oldest running start and advances locally across snapshots`() {
        val clock = TestTimeSource()
        val first =
            snapshot(
                turn("old", "running", startedAt = "2026-10-27T09:58:30Z"),
                turn("new", "running", startedAt = "2026-10-27T09:59:00Z"),
            )
        val initial = first.turnStatuses(receivedAt = clock.markNow()).getValue("claude").getValue("ses")
        clock += 10.seconds
        assertEquals(100.seconds, initial.since?.elapsedNow())

        val refreshed =
            first
                .copy(observedAt = "2026-10-27T10:00:10Z")
                .turnStatuses(receivedAt = clock.markNow())
                .getValue("claude")
                .getValue("ses")
        clock += 2.seconds
        assertEquals(102.seconds, refreshed.since?.elapsedNow())
    }

    @Test
    fun `finished age follows the selected outcome change time rather than its start`() {
        val clock = TestTimeSource()
        val activity =
            snapshot(
                turn("old", "succeeded", startedAt = "2026-10-27T09:00:00Z")
                    .copy(changedAt = "2026-10-27T09:59:50Z"),
                turn("new", "succeeded", startedAt = "2026-10-27T09:55:00Z")
                    .copy(changedAt = "2026-10-27T09:58:40Z"),
            ).turnStatuses(receivedAt = clock.markNow()).getValue("claude").getValue("ses")

        clock += 5.seconds
        assertEquals(TurnStatus.FINISHED, activity.status)
        assertEquals(85.seconds, activity.since?.elapsedNow())
    }

    @Test
    fun `future gateway times clamp to zero and unreadable times omit timing`() {
        val clock = TestTimeSource()
        val future =
            snapshot(turn("gs", "running", startedAt = "2026-10-27T10:00:05Z"))
                .turnStatuses(receivedAt = clock.markNow())
                .getValue("claude")
                .getValue("ses")
        assertEquals(0.seconds, future.since?.elapsedNow())
        val malformed =
            snapshot(turn("gs", "running", startedAt = "bad"))
                .turnStatuses(receivedAt = clock.markNow())
                .getValue("claude")
                .getValue("ses")
        assertEquals(TurnStatus.WORKING, malformed.status)
        assertNull(malformed.since)
    }

    @Test
    fun `a running turn outranks an obsolete outcome on another gateway session`() {
        val statuses =
            snapshot(
                turn("gs_1", "failed", startedAt = "2026-10-27T09:00:00Z"),
                turn("gs_2", "running", startedAt = "2026-10-27T09:30:00Z"),
            ).statuses()

        assertEquals(mapOf("claude" to mapOf("ses" to TurnStatus.WORKING)), statuses)
    }

    @Test
    fun `a pending request on either running turn needs attention`() {
        val statuses =
            snapshot(
                turn("gs_1", "running", attention = listOf("permission")),
                turn("gs_2", "running"),
            ).statuses()

        assertEquals(TurnStatus.NEEDS_ATTENTION, statuses.getValue("claude").getValue("ses"))
    }

    @Test
    fun `the most recently started outcome wins whatever the order`() {
        val statuses =
            snapshot(
                turn("gs_1", "succeeded", startedAt = "2026-10-27T09:00:00Z"),
                turn("gs_2", "failed", startedAt = "2026-10-27T09:30:00Z"),
            ).statuses()

        assertEquals(TurnStatus.ERROR, statuses.getValue("claude").getValue("ses"))
    }

    @Test
    fun `sub-millisecond start times decide the winner, not the later end`() {
        val statuses =
            snapshot(
                turn(
                    "gs_1",
                    "succeeded",
                    startedAt = "2026-10-27T09:00:00.100000001Z",
                ).copy(changedAt = "2026-10-27T09:05:00Z"),
                turn(
                    "gs_2",
                    "failed",
                    startedAt = "2026-10-27T09:00:00.100000009Z",
                ),
            ).statuses()

        assertEquals(
            "the turn that started 8ns later is the current one, though the older one ended later",
            TurnStatus.ERROR,
            statuses.getValue("claude").getValue("ses"),
        )
    }

    @Test
    fun `an outcome whose start time cannot be read claims nothing`() {
        assertEquals(
            TurnStatus.UNKNOWN,
            snapshot(turn("gs_1", "succeeded", startedAt = "not-a-time"))
                .statuses()
                .getValue("claude")
                .getValue("ses"),
        )
        assertEquals(
            TurnStatus.UNKNOWN,
            snapshot(turn("gs_1", "failed", startedAt = null))
                .statuses()
                .getValue("claude")
                .getValue("ses"),
        )
    }

    @Test
    fun `a cancelled or unrecognised state claims no success`() {
        val statuses =
            snapshot(
                turn("gs_1", "succeeded", startedAt = "2026-10-27T09:00:00Z"),
                turn("gs_2", "cancelled", startedAt = "2026-10-27T09:30:00Z"),
            ).statuses()
        assertEquals(TurnStatus.UNKNOWN, statuses.getValue("claude").getValue("ses"))

        assertEquals(
            TurnStatus.UNKNOWN,
            snapshot(turn("gs_1", "paused")).statuses().getValue("claude").getValue("ses"),
        )
    }

    @Test
    fun `the same ACP session on two agents stays separate`() {
        val statuses =
            snapshot(
                turn("gs_1", "running", agentId = "claude"),
                turn("gs_2", "succeeded", agentId = "codex"),
            ).statuses()

        assertEquals(TurnStatus.WORKING, statuses.getValue("claude").getValue("ses"))
        assertEquals(TurnStatus.FINISHED, statuses.getValue("codex").getValue("ses"))
    }

    private fun GatewayTurnsSnapshot.statuses() =
        turnStatuses().mapValues { (_, sessions) -> sessions.mapValues { it.value.status } }

    private fun snapshot(vararg turns: GatewayTurn) =
        GatewayTurnsSnapshot(
            gatewayStartedAt = "2026-10-27T08:00:00Z",
            observedAt = "2026-10-27T10:00:00Z",
            turns = turns.toList(),
        )

    private fun turn(
        gatewaySessionId: String,
        state: String,
        agentId: String = "claude",
        attention: List<String> = emptyList(),
        startedAt: String? = "2026-10-27T09:00:00Z",
    ) = GatewayTurn(
        agentId = agentId,
        sessionId = "ses",
        gatewaySessionId = gatewaySessionId,
        state = state,
        attention = attention,
        startedAt = startedAt,
        changedAt = startedAt,
    )
}
