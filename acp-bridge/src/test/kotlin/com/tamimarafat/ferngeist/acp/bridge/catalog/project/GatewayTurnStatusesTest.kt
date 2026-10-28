package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurn
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayTurnStatusesTest {
    @Test
    fun `a running turn outranks an obsolete outcome on another gateway session`() {
        val statuses =
            snapshot(
                turn("gs_1", "failed", startedAt = "2026-10-27T09:00:00Z"),
                turn("gs_2", "running", startedAt = "2026-10-27T09:30:00Z"),
            ).turnStatuses()

        assertEquals(mapOf("claude" to mapOf("ses" to TurnStatus.WORKING)), statuses)
    }

    @Test
    fun `a pending request on either running turn needs attention`() {
        val statuses =
            snapshot(
                turn("gs_1", "running", attention = listOf("permission")),
                turn("gs_2", "running"),
            ).turnStatuses()

        assertEquals(TurnStatus.NEEDS_ATTENTION, statuses.getValue("claude").getValue("ses"))
    }

    @Test
    fun `the most recently started outcome wins whatever the order`() {
        val statuses =
            snapshot(
                turn("gs_1", "succeeded", startedAt = "2026-10-27T09:00:00Z"),
                turn("gs_2", "failed", startedAt = "2026-10-27T09:30:00Z"),
            ).turnStatuses()

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
            ).turnStatuses()

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
                .turnStatuses()
                .getValue("claude")
                .getValue("ses"),
        )
        assertEquals(
            TurnStatus.UNKNOWN,
            snapshot(turn("gs_1", "failed", startedAt = null))
                .turnStatuses()
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
            ).turnStatuses()
        assertEquals(TurnStatus.UNKNOWN, statuses.getValue("claude").getValue("ses"))

        assertEquals(
            TurnStatus.UNKNOWN,
            snapshot(turn("gs_1", "paused")).turnStatuses().getValue("claude").getValue("ses"),
        )
    }

    @Test
    fun `the same ACP session on two agents stays separate`() {
        val statuses =
            snapshot(
                turn("gs_1", "running", agentId = "claude"),
                turn("gs_2", "succeeded", agentId = "codex"),
            ).turnStatuses()

        assertEquals(TurnStatus.WORKING, statuses.getValue("claude").getValue("ses"))
        assertEquals(TurnStatus.FINISHED, statuses.getValue("codex").getValue("ses"))
    }

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
