package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import org.junit.Assert.assertEquals
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
        val ordered = projectsByActivity(projects.reversed(), emptySet())
        assertEquals(
            listOf("/1", "/2", "/3", "/4", "/5"),
            collapsedProjects(ordered, now, emptySet()).map { it.repo },
        )
    }

    @Test
    fun `collapsed selection keeps old and undated live projects ahead of recent idle projects`() {
        val now = 200_000_000L
        val projects =
            (1L..6L).map { project("/idle-$it", now - it) } +
                listOf(project("/live-old", 1), project("/live-undated", null))
        val streaming = setOf("agent//live-old", "agent//live-undated")
        val ordered = projectsByActivity(projects, streaming)

        assertEquals(
            listOf("/live-old", "/live-undated", "/idle-1", "/idle-2", "/idle-3"),
            collapsedProjects(ordered, now, streaming).map { it.repo },
        )
    }

    @Test
    fun `live priority changes back to recency when streaming stops`() {
        val projects = listOf(project("/idle", 20), project("/live", 10), project("/unknown", null))

        assertEquals(
            listOf("/live", "/idle", "/unknown"),
            projectsByActivity(projects, setOf("agent//live")).map { it.repo },
        )
        assertEquals(
            listOf("/idle", "/live", "/unknown"),
            projectsByActivity(projects, emptySet()).map { it.repo },
        )
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
