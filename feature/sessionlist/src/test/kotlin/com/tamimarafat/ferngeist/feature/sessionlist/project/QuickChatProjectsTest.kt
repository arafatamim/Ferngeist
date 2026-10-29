package com.tamimarafat.ferngeist.feature.sessionlist.project

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.model.GatewaySource
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickChatProjectsTest {
    @Test
    fun `shows only six newest projects including those without chats`() {
        val gateway = GatewaySource(id = "gw", name = "Host", host = "host", gatewayCredential = "token")
        val projects = (0L..7L).map { ProjectSummary(gateway, "/repo/$it", emptyList(), null, it) }

        assertEquals(listOf(7L, 6L, 5L, 4L, 3L, 2L), quickChatProjects(projects).map { it.updatedAt })
    }
}
