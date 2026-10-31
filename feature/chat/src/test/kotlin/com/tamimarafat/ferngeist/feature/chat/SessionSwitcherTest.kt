package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.WorktreeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSwitcherTest {
    private val serverA = SwitcherServer(id = "srvA", name = "Alpha", iconUrl = "alpha.svg")
    private val serverB = SwitcherServer(id = "srvB", name = "Beta", iconUrl = "beta.svg")

    private fun summary(
        id: String,
        updatedAt: Long?,
        cwd: String? = "/w",
    ) = SessionSummary(id = id, title = "T-$id", cwd = cwd, updatedAt = updatedAt)

    @Test
    fun `bubble shows highest priority outcome from other sessions`() {
        fun state(vararg statuses: Pair<TurnStatus, Boolean>): SwitcherUiState =
            SwitcherUiState(
                groups =
                    listOf(
                        SwitcherGroup(
                            "project:srvA|/w",
                            "w",
                            statuses.mapIndexed { index, (status, current) ->
                                SwitcherSession("srvA", "$index", null, null, null, TurnActivity(status), current)
                            },
                        ),
                    ),
            )

        assertEquals(
            TurnStatus.NEEDS_ATTENTION,
            state(TurnStatus.ERROR to false, TurnStatus.NEEDS_ATTENTION to false).badgeStatus,
        )
        assertEquals(TurnStatus.ERROR, state(TurnStatus.FINISHED to false, TurnStatus.ERROR to false).badgeStatus)
        assertEquals(TurnStatus.FINISHED, state(TurnStatus.WORKING to false, TurnStatus.FINISHED to false).badgeStatus)
        assertEquals(
            TurnStatus.FINISHED,
            state(TurnStatus.NEEDS_ATTENTION to true, TurnStatus.FINISHED to false).badgeStatus,
        )
        assertEquals(null, state(TurnStatus.ERROR to true, TurnStatus.WORKING to false).badgeStatus)
    }

    @Test
    fun `includes current session flagged and keeps rows newest first`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA),
                sessionsByServer = mapOf("srvA" to listOf(summary("s1", 30L), summary("s2", 10L))),
                liveIdsByServer = mapOf("srvA" to setOf("s1", "s2")),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "s1"),
            )
        val group = groups.single()
        assertEquals("w", group.projectName)
        assertEquals(listOf("s1", "s2"), group.sessions.map { it.sessionId })
        assertEquals(listOf("Alpha", "Alpha"), group.sessions.map { it.agentName })
        assertTrue(group.sessions[0].isCurrent)
        assertFalse(group.sessions[1].isCurrent)

        // A solo live session still yields its group with only the current row.
        val solo =
            deriveSwitcherGroups(
                servers = listOf(serverA),
                sessionsByServer = mapOf("srvA" to listOf(summary("s1", 1L))),
                liveIdsByServer = mapOf("srvA" to setOf("s1")),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "s1"),
            )
        assertEquals(listOf("s1"), solo.single().sessions.map { it.sessionId })
        assertTrue(
            solo
                .single()
                .sessions
                .single()
                .isCurrent,
        )
    }

    @Test
    fun `worktrees of one repo share its group while worktrees keep their branches`() {
        val repo = "/orca/workspaces/Ferngeist"
        val branchX = "$repo/.worktrees/feat-x"
        val branchY = "$repo/.worktrees/feat-y"
        val groups =
            deriveSwitcherGroups(
                servers =
                    listOf(
                        serverA.copy(
                            worktrees =
                                listOf(
                                    WorktreeRef(repo = repo, path = branchX, branch = "feat-x"),
                                    WorktreeRef(repo = repo, path = branchY, branch = "feat-y"),
                                ),
                        ),
                    ),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("wt-x", 10L, branchX), summary("wt-y", 20L, branchY)),
                    ),
                liveIdsByServer = mapOf("srvA" to setOf("wt-x", "wt-y")),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "other"),
            )

        val group = groups.single()
        assertEquals("Ferngeist", group.projectName)
        assertEquals(listOf("wt-y", "wt-x"), group.sessions.map { it.sessionId })
        assertEquals(listOf("feat-y", "feat-x"), group.sessions.map { it.worktree?.branch })
    }

    @Test
    fun `agents on one gateway share a project group`() {
        val gateway = "gateway-1"
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA.copy(sourceId = gateway), serverB.copy(sourceId = gateway)),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("a1", 5L, "/repo")),
                        "srvB" to listOf(summary("b1", 50L, "/repo")),
                    ),
                liveIdsByServer = mapOf("srvA" to setOf("a1"), "srvB" to setOf("b1")),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "a1"),
            )

        val group = groups.single()
        assertEquals("repo", group.projectName)
        // The current row pins first, then recency: one group across both agents.
        assertEquals(listOf("a1", "b1"), group.sessions.map { it.sessionId })
        assertEquals(listOf("Alpha", "Beta"), group.sessions.map { it.agentName })
        assertEquals(listOf("alpha.svg", "beta.svg"), group.sessions.map { it.agentIconUrl })
    }

    @Test
    fun `distinct gateways with the same project name stay separate`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA.copy(sourceId = "gateway-1"), serverB.copy(sourceId = "gateway-2")),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("a1", 5L, "/work/one/Ferngeist")),
                        "srvB" to listOf(summary("b1", 50L, "/work/two/Ferngeist")),
                    ),
                liveIdsByServer = mapOf("srvA" to setOf("a1"), "srvB" to setOf("b1")),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "zzz"),
            )

        assertEquals(2, groups.size)
        assertEquals(listOf("Ferngeist", "Ferngeist"), groups.map { it.projectName })
        assertNotEquals(groups[0].projectKey, groups[1].projectKey)
        assertEquals(listOf("b1"), groups[0].sessions.map { it.sessionId })
        assertEquals(listOf("a1"), groups[1].sessions.map { it.sessionId })
    }

    @Test
    fun `sessions without a cwd group as one unknown project per server`() {
        val gateway = "gateway-1"
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA.copy(sourceId = gateway), serverB.copy(sourceId = gateway)),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("a1", 10L, null), summary("a2", 30L, "")),
                        "srvB" to listOf(summary("b1", 50L, null)),
                    ),
                liveIdsByServer =
                    mapOf(
                        "srvA" to setOf("a1", "a2"),
                        "srvB" to setOf("b1"),
                    ),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "zzz"),
            )

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.projectName == null })
        assertNotEquals(groups[0].projectKey, groups[1].projectKey)
        assertEquals(listOf("b1"), groups[0].sessions.map { it.sessionId })
        assertEquals(listOf("a2", "a1"), groups[1].sessions.map { it.sessionId })
        assertEquals(listOf("srvB"), groups[0].sessions.map { it.serverId }.distinct())
        assertEquals(listOf("srvA"), groups[1].sessions.map { it.serverId }.distinct())
    }

    @Test
    fun `one server splits different repo roots into separate projects`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("a1", 10L, "/one/alpha"), summary("a2", 20L, "/two/beta")),
                    ),
                liveIdsByServer = mapOf("srvA" to setOf("a1", "a2")),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "zzz"),
            )

        assertEquals(listOf("beta", "alpha"), groups.map { it.projectName })
        assertNotEquals(groups[0].projectKey, groups[1].projectKey)
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
            turnStatus = { _, _ -> TurnActivity() },
            current = SwitcherCurrent(currentServer, currentSession),
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
        val prev = SwitcherSession("srvA", "p", null, null, null, TurnActivity(), false)
        val next = SwitcherSession("srvA", "n", null, null, null, TurnActivity(), false)
        // Slow drag past the distance threshold follows travel direction.
        assertEquals("n", resolveSwipeTarget(-60f, 0f, SwipeThresholds(48f, 1000f), prev, next)?.sessionId)
        assertEquals("p", resolveSwipeTarget(60f, 0f, SwipeThresholds(48f, 1000f), prev, next)?.sessionId)
        // Short of the distance but fast and agreeing: velocity commits it.
        assertEquals("n", resolveSwipeTarget(-10f, -1500f, SwipeThresholds(48f, 1000f), prev, next)?.sessionId)
        assertEquals("p", resolveSwipeTarget(10f, 1500f, SwipeThresholds(48f, 1000f), prev, next)?.sessionId)
        // A disagreeing release wiggle never overrules an established drag…
        assertEquals("n", resolveSwipeTarget(-200f, 1500f, SwipeThresholds(48f, 1000f), prev, next)?.sessionId)
        // …and without established travel it only earns a snap-back.
        assertEquals(null, resolveSwipeTarget(-10f, 1500f, SwipeThresholds(48f, 1000f), prev, next))
        // Short and slow springs back; missing neighbor commits to nothing.
        assertEquals(null, resolveSwipeTarget(-10f, 0f, SwipeThresholds(48f, 1000f), prev, next))
        assertEquals(null, resolveSwipeTarget(0f, 0f, SwipeThresholds(48f, 1000f), prev, next))
        assertEquals(null, resolveSwipeTarget(-60f, 0f, SwipeThresholds(48f, 1000f), prev, null))
    }

    @Test
    fun `neighbors are null without two sessions or a current row`() {
        assertEquals(null to null, switcherNeighbors(emptyList()))
        val solo =
            deriveSwitcherGroups(
                servers = listOf(serverA),
                sessionsByServer = mapOf("srvA" to listOf(summary("s1", 1L))),
                liveIdsByServer = mapOf("srvA" to setOf("s1")),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "s1"),
            )
        assertEquals(null to null, switcherNeighbors(solo))
        val noCurrent =
            listOf(
                SwitcherGroup(
                    projectKey = "project:srvA|/w",
                    projectName = "w",
                    sessions =
                        listOf(
                            SwitcherSession("srvA", "s1", null, null, null, TurnActivity(), false),
                            SwitcherSession("srvA", "s2", null, null, null, TurnActivity(), false),
                        ),
                ),
            )
        assertEquals(null to null, switcherNeighbors(noCurrent))
    }

    @Test
    fun `separate servers stay separate groups ordered by newest row`() {
        val groups =
            deriveSwitcherGroups(
                servers = listOf(serverA, serverB),
                sessionsByServer =
                    mapOf(
                        "srvA" to listOf(summary("a1", 5L)),
                        "srvB" to listOf(summary("b1", 50L)),
                    ),
                liveIdsByServer = mapOf("srvA" to setOf("a1"), "srvB" to setOf("b1")),
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "zzz"),
            )
        assertEquals(
            listOf(listOf("b1"), listOf("a1")),
            groups.map { group -> group.sessions.map { it.sessionId } },
        )
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
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvB", "bcur"),
            )
        assertEquals(
            listOf(listOf("bcur", "b1"), listOf("a1")),
            groups.map { group -> group.sessions.map { it.sessionId } },
        )
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
                turnStatus = { _, _ -> TurnActivity() },
                current = SwitcherCurrent("srvA", "zzz"),
            )
        assertEquals(listOf("new", "old"), groups.single().sessions.map { it.sessionId })
    }
}
