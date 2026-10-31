package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectChoicesTest {
    private val gateway = GatewaySource(id = "gw", name = "PC", host = "pc", gatewayCredential = "token")

    @Test
    fun `collapsed projects have known activity strictly within 48 hours and newest five win`() {
        val now = 200_000_000L
        val limit = 172_800_000L
        val projects =
            (1L..6L).map { project("/$it", now - it) } +
                listOf(project("/boundary", now - limit), project("/unknown", null))
        val ordered = projectsByActivity(projects.reversed(), emptyMap())
        assertEquals(
            listOf("/1", "/2", "/3", "/4", "/5"),
            collapsedProjects(ordered, now, emptyMap()).map { it.repo },
        )
    }

    @Test
    fun `collapsed selection keeps old and undated live projects ahead of recent idle projects`() {
        val now = 200_000_000L
        val projects =
            (1L..6L).map { project("/idle-$it", now - it) } +
                listOf(project("/live-old", 1), project("/live-undated", null))
        val statuses =
            mapOf(
                "agent//live-old" to TurnActivity(TurnStatus.WORKING),
                "agent//live-undated" to TurnActivity(TurnStatus.NEEDS_ATTENTION),
            )
        val ordered = projectsByActivity(projects, statuses)

        assertEquals(
            listOf("/live-old", "/live-undated", "/idle-1", "/idle-2", "/idle-3"),
            collapsedProjects(ordered, now, statuses).map { it.repo },
        )
    }

    @Test
    fun `live priority changes back to recency when streaming stops`() {
        val projects = listOf(project("/idle", 20), project("/live", 10), project("/unknown", null))

        assertEquals(
            listOf("/live", "/idle", "/unknown"),
            projectsByActivity(projects, mapOf("agent//live" to TurnActivity(TurnStatus.WORKING))).map { it.repo },
        )
        assertEquals(
            listOf("/idle", "/live", "/unknown"),
            projectsByActivity(projects, emptyMap()).map { it.repo },
        )
    }

    @Test
    fun `working chats match target and chat together, newest first, and empty when streaming stops`() {
        val first = project("/repo", 1).chats.single().let { it.copy(summary = it.summary.copy(updatedAt = 1)) }
        val second = first.copy(summary = first.summary.copy(id = "second", updatedAt = 2))
        val project = project("/repo", 1).copy(chats = listOf(first, second))

        assertEquals(
            listOf("second"),
            project.workingChatIds(
                mapOf(
                    "other//repo" to TurnActivity(TurnStatus.WORKING),
                    "agent/second" to TurnActivity(TurnStatus.WORKING),
                    "agent/unrelated" to TurnActivity(TurnStatus.WORKING),
                ),
            ),
        )
        assertEquals(
            listOf("second", "/repo"),
            project.workingChatIds(
                mapOf(
                    "agent//repo" to TurnActivity(TurnStatus.WORKING),
                    "agent/second" to TurnActivity(TurnStatus.WORKING),
                ),
            ),
        )
        assertEquals(emptyList<String>(), project.workingChatIds(emptyMap()))
    }

    @Test
    fun `project status aggregates its own chats by full identity with attention first`() {
        val first = project("/repo", 1).chats.single()
        val second = first.copy(summary = first.summary.copy(id = "second"))
        val project = project("/repo", 1).copy(chats = listOf(first, second))

        assertEquals(
            TurnActivity(TurnStatus.ERROR),
            project.turnStatus(
                mapOf(
                    "agent//repo" to TurnActivity(TurnStatus.FINISHED),
                    "agent/second" to TurnActivity(TurnStatus.ERROR),
                    "other//repo" to TurnActivity(TurnStatus.NEEDS_ATTENTION),
                ),
            ),
        )
        assertEquals(
            TurnActivity(TurnStatus.UNKNOWN),
            project.turnStatus(
                mapOf(
                    "other//repo" to TurnActivity(TurnStatus.FINISHED),
                    "other/second" to TurnActivity(TurnStatus.WORKING),
                ),
            ),
        )
    }

    @Test
    fun `finished never counts as in flight and attention does`() {
        val project = project("/repo", 1)
        assertFalse(project.hasActiveTurn(mapOf("agent//repo" to TurnActivity(TurnStatus.FINISHED))))
        assertTrue(project.hasActiveTurn(mapOf("agent//repo" to TurnActivity(TurnStatus.NEEDS_ATTENTION))))
        assertTrue(project.hasActiveTurn(mapOf("agent//repo" to TurnActivity(TurnStatus.WORKING))))
        assertFalse(project.hasActiveTurn(mapOf("agent//repo" to TurnActivity(TurnStatus.ERROR))))
    }

    @Test
    fun `an error label does not hide a project whose other chat is still working`() {
        val old = project("/old", null)
        val running = old.chats.single().copy(summary = SessionSummary(id = "running"))
        val mixed = old.copy(chats = old.chats + running)
        val statuses =
            mapOf(
                "agent//old" to TurnActivity(TurnStatus.ERROR),
                "agent/running" to TurnActivity(TurnStatus.WORKING),
            )
        val ordered = projectsByActivity(listOf(project("/recent", 199_999_999L), mixed), statuses)

        assertEquals(TurnActivity(TurnStatus.ERROR), mixed.turnStatus(statuses))
        assertEquals(listOf("/old", "/recent"), ordered.map { it.repo })
        assertEquals(listOf("/old", "/recent"), collapsedProjects(ordered, 200_000_000L, statuses).map { it.repo })
    }

    @Test
    fun `live chat membership does not leak to another project on the same agent`() {
        val first = project("/first", 1)
        val second = project("/second", 1)
        val connected = setOf("agent//first")

        assertTrue(first.hasLiveChat(connected))
        assertFalse(second.hasLiveChat(connected))
        assertFalse(first.hasLiveChat(setOf("other//first")))
        assertFalse(first.hasLiveChat(emptySet()))
    }

    private fun project(
        repo: String,
        updatedAt: Long?,
    ) = ProjectSummary(
        gateway,
        repo,
        listOf(
            ProjectChat(
                SessionSummary(id = repo),
                LaunchableTarget.GatewayAgent(
                    GatewayAgentBinding(id = "agent", name = "Agent", gatewaySourceId = gateway.id, agentId = "agent"),
                    gateway,
                ),
                null,
            ),
        ),
        null,
        updatedAt,
    )
}
