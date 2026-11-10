package com.tamimarafat.ferngeist.service

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.LiveTurn
import com.tamimarafat.ferngeist.core.model.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateContentTest {
    @Test
    fun `a single turn with a plan reports its current step and completed count`() {
        val content =
            liveUpdateContent(
                turns =
                    listOf(
                        turn(
                            status = TurnStatus.WORKING,
                            plan = plan("a" to COMPLETED, "b" to IN_PROGRESS, "c" to PENDING),
                        ),
                    ),
                agentNames = mapOf("srv" to "Claude"),
                sessionTitles = mapOf("srv/s1" to "Fix login"),
                nowMs = 10_000,
            ) as LiveUpdateContent.PlanStep

        assertEquals(2, content.current)
        assertEquals(3, content.total)
        assertEquals("b", content.step)
        assertEquals(1, content.completed)
        assertEquals("Claude", content.turn.agentName)
        assertEquals("Fix login", content.turn.sessionTitle)
    }

    @Test
    fun `a plan with every step done keeps the last step as the current one`() {
        val content =
            liveUpdateContent(
                turns = listOf(turn(status = TurnStatus.WORKING, plan = plan("a" to COMPLETED, "b" to COMPLETED))),
                agentNames = emptyMap(),
                sessionTitles = emptyMap(),
                nowMs = 10_000,
            ) as LiveUpdateContent.PlanStep

        assertEquals(2, content.current)
        assertEquals(2, content.completed)
    }

    @Test
    fun `a single turn without a plan uses the chronometer, its branch and its activity`() {
        val content =
            liveUpdateContent(
                turns =
                    listOf(
                        LiveTurn(
                            presence =
                                ChatPresence(
                                    serverId = "srv",
                                    sessionId = "s1",
                                    cwd = "/repo/.worktrees/feat-x",
                                ),
                            status = TurnStatus.WORKING,
                            startedAtEpochMs = 4_000,
                            activity = "Editing Login.kt",
                        ),
                    ),
                agentNames = mapOf("srv" to "Claude"),
                sessionTitles = mapOf("srv/s1" to "Fix login"),
                nowMs = 10_000,
            ) as LiveUpdateContent.Working

        assertEquals("feat-x", content.branch)
        assertEquals("Editing Login.kt", content.activity)
        assertEquals(4_000L, content.startedAtEpochMs)
    }

    @Test
    fun `a turn the runtime gives no start falls back to the render time`() {
        val content =
            liveUpdateContent(
                turns = listOf(turn(status = TurnStatus.WORKING, startedAtEpochMs = null)),
                agentNames = emptyMap(),
                sessionTitles = emptyMap(),
                nowMs = 7_000,
            ) as LiveUpdateContent.Working

        assertEquals(7_000L, content.startedAtEpochMs)
    }

    @Test
    fun `several turns collapse into one aggregate with blocked agents first`() {
        val content =
            liveUpdateContent(
                turns =
                    listOf(
                        turn(sessionId = "working", status = TurnStatus.WORKING, startedAtEpochMs = 8_000),
                        turn(sessionId = "blocked", status = TurnStatus.NEEDS_ATTENTION, startedAtEpochMs = 9_000),
                    ),
                agentNames = mapOf("srv" to "Claude"),
                sessionTitles = mapOf("srv/blocked" to "Blocked chat", "srv/working" to "Working chat"),
                nowMs = 10_000,
            ) as LiveUpdateContent.Aggregate

        assertEquals(2, content.busyCount)
        assertTrue(content.needsYou)
        assertEquals(listOf("blocked", "working"), content.lines.map { it.turn.presence.sessionId })
        assertTrue(content.lines.first().needsYou)
        assertEquals(1_000L, content.lines.first().elapsedMs)
    }

    @Test
    fun `no live turns produce no notification`() {
        assertNull(
            liveUpdateContent(
                turns = emptyList(),
                agentNames = emptyMap(),
                sessionTitles = emptyMap(),
                nowMs = 10_000,
            ),
        )
    }

    private fun turn(
        status: TurnStatus,
        plan: List<PlanEntry> = emptyList(),
        sessionId: String = "s1",
        startedAtEpochMs: Long? = 5_000,
    ) = LiveTurn(
        presence = ChatPresence(serverId = "srv", sessionId = sessionId),
        status = status,
        startedAtEpochMs = startedAtEpochMs,
        planEntries = plan,
    )

    private fun plan(vararg entries: Pair<String, PlanEntryStatus>) =
        entries.map { (content, status) ->
            PlanEntry(content = content, priority = PlanEntryPriority.MEDIUM, status = status)
        }

    private companion object {
        val COMPLETED = PlanEntryStatus.COMPLETED
        val IN_PROGRESS = PlanEntryStatus.IN_PROGRESS
        val PENDING = PlanEntryStatus.PENDING
    }
}
