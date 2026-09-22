package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSwitcherTest {
    private val serverA = SwitcherServer(id = "srvA", name = "Alpha")
    private val serverB = SwitcherServer(id = "srvB", name = "Beta")

    private fun summary(
        id: String,
        updatedAt: Long?,
    ) = SessionSummary(id = id, title = "T-$id", cwd = "/w", updatedAt = updatedAt)

    @Test
    fun `includes current session flagged and keeps rows newest first`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA),
                sessionsByServer = mapOf("srvA" to listOf(summary("s1", 30L), summary("s2", 10L))),
                liveIdsByServer = mapOf("srvA" to setOf("s1", "s2")),
                isGenerating = { _, _ -> false },
                currentServerId = "srvA",
                currentSessionId = "s1",
            )
        assertEquals(1, groups.size)
        assertEquals(listOf("s1", "s2"), groups[0].sessions.map { it.sessionId })
        assertTrue(groups[0].sessions[0].isCurrent)
        assertFalse(groups[0].sessions[1].isCurrent)
    }

    @Test
    fun `neighbors cycle all sessions in recency order despite current pinning`() {
        fun groupsFor(
            currentServer: String,
            currentSession: String,
        ) = deriveSwitcherGroups(
            servers = listOf(serverA, serverB),
            sessionsByServer =
                mapOf(
                    "srvA" to listOf(summary("a1", 100L)),
                    "srvB" to listOf(summary("b1", 50L), summary("bcur", 1L)),
                ),
            liveIdsByServer = mapOf("srvA" to setOf("a1"), "srvB" to setOf("b1", "bcur")),
            isGenerating = { _, _ -> false },
            currentServerId = currentServer,
            currentSessionId = currentSession,
        )
        // Display order re-anchors per screen ([bcur, b1, a1] here), but the
        // cycle follows recency [a1, b1, bcur] on every screen: no ping-pong.
        val (_, nextFromBcur) = switcherNeighbors(groupsFor("srvB", "bcur"))
        assertEquals("a1", nextFromBcur?.sessionId)
        val (_, nextFromA1) = switcherNeighbors(groupsFor("srvA", "a1"))
        assertEquals("b1", nextFromA1?.sessionId)
        val (prevFromA1, _) = switcherNeighbors(groupsFor("srvA", "a1"))
        assertEquals("bcur", prevFromA1?.sessionId)
    }

    @Test
    fun `resolveSwipeTarget commits on distance or fling velocity`() {
        val prev = SwitcherSession("srvA", "p", null, null, null, false, false)
        val next = SwitcherSession("srvA", "n", null, null, null, false, false)
        // Slow drag past the distance threshold follows travel direction.
        assertEquals("n", resolveSwipeTarget(-60f, 0f, 48f, 1000f, prev, next)?.sessionId)
        assertEquals("p", resolveSwipeTarget(60f, 0f, 48f, 1000f, prev, next)?.sessionId)
        // Short of the distance but fast and agreeing: velocity commits it.
        assertEquals("n", resolveSwipeTarget(-10f, -1500f, 48f, 1000f, prev, next)?.sessionId)
        assertEquals("p", resolveSwipeTarget(10f, 1500f, 48f, 1000f, prev, next)?.sessionId)
        // A disagreeing release wiggle never overrules an established drag…
        assertEquals("n", resolveSwipeTarget(-200f, 1500f, 48f, 1000f, prev, next)?.sessionId)
        // …and without established travel it only earns a snap-back.
        assertEquals(null, resolveSwipeTarget(-10f, 1500f, 48f, 1000f, prev, next))
        // Short and slow springs back; missing neighbor commits to nothing.
        assertEquals(null, resolveSwipeTarget(-10f, 0f, 48f, 1000f, prev, next))
        assertEquals(null, resolveSwipeTarget(0f, 0f, 48f, 1000f, prev, next))
        assertEquals(null, resolveSwipeTarget(-60f, 0f, 48f, 1000f, prev, null))
    }

    @Test
    fun `neighbors are null without two sessions or a current row`() {
        assertEquals(null to null, switcherNeighbors(emptyList()))
        val solo =
            deriveSwitcherGroups(
                servers = listOf(serverA),
                sessionsByServer = mapOf("srvA" to listOf(summary("s1", 1L))),
                liveIdsByServer = mapOf("srvA" to setOf("s1")),
                isGenerating = { _, _ -> false },
                currentServerId = "srvA",
                currentSessionId = "s1",
            )
        assertEquals(null to null, switcherNeighbors(solo))
        val noCurrent =
            listOf(
                SwitcherGroup(
                    serverId = "srvA",
                    serverName = "Alpha",
                    sessions =
                        listOf(
                            SwitcherSession("srvA", "s1", null, null, null, false, false),
                            SwitcherSession("srvA", "s2", null, null, null, false, false),
                        ),
                ),
            )
        assertEquals(null to null, switcherNeighbors(noCurrent))
    }

    @Test
    fun `solo live session yields its group with only the current row`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA),
                sessionsByServer = mapOf("srvA" to listOf(summary("s1", 1L))),
                liveIdsByServer = mapOf("srvA" to setOf("s1")),
                isGenerating = { _, _ -> false },
                currentServerId = "srvA",
                currentSessionId = "s1",
            )
        assertEquals(1, groups.size)
        assertTrue(groups[0].sessions[0].isCurrent)
    }

    @Test
    fun `groups span servers ordered by newest row`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA, serverB),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("a1", 5L)),
                        "srvB" to listOf(summary("b1", 50L)),
                    ),
                liveIdsByServer = mapOf("srvA" to setOf("a1"), "srvB" to setOf("b1")),
                isGenerating = { _, _ -> false },
                currentServerId = "srvA",
                currentSessionId = "zzz",
            )
        assertEquals(listOf("srvB", "srvA"), groups.map { it.serverId })
    }

    @Test
    fun `current session pins to top despite older timestamp`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA, serverB),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("a1", 100L)),
                        "srvB" to listOf(summary("b1", 50L), summary("bcur", 1L)),
                    ),
                liveIdsByServer = mapOf("srvA" to setOf("a1"), "srvB" to setOf("b1", "bcur")),
                isGenerating = { _, _ -> false },
                currentServerId = "srvB",
                currentSessionId = "bcur",
            )
        assertEquals(listOf("srvB", "srvA"), groups.map { it.serverId })
        assertEquals(listOf("bcur", "b1"), groups[0].sessions.map { it.sessionId })
    }

    @Test
    fun `null updatedAt sorts last and unknown servers are skipped`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("old", null), summary("new", 9L)),
                    ),
                liveIdsByServer = mapOf("srvA" to setOf("old", "new"), "ghost" to setOf("g1")),
                isGenerating = { _, _ -> false },
                currentServerId = "srvA",
                currentSessionId = "zzz",
            )
        assertEquals(listOf("new", "old"), groups[0].sessions.map { it.sessionId })
    }
}
