package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.CardTone
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectCardPresentationTest {
    private val gateway = GatewaySource(id = "gw", name = "PC", host = "pc", gatewayCredential = "token")

    @Test
    fun `working, unread finished, attention and error chats each promote a project to the hero tile`() {
        for (status in listOf(TurnStatus.WORKING, TurnStatus.FINISHED, TurnStatus.NEEDS_ATTENTION, TurnStatus.ERROR)) {
            val card = projectCardPresentation(project("/repo"), chatStatuses(status), emptySet())

            assertEquals(ProjectCardVariant.HERO, card.variant)
            assertEquals(status, card.activity.status)
        }
    }

    @Test
    fun `hero tone stays primary only while a chat is live on this device`() {
        val statuses = chatStatuses(TurnStatus.WORKING)
        val resting = projectCardPresentation(project("/repo"), statuses, emptySet())
        val live = projectCardPresentation(project("/repo"), statuses, setOf("agent/s1"))

        assertEquals(CardTone.DEFAULT, resting.tone)
        assertEquals(CardTone.PRIMARY, live.tone)
    }

    @Test
    fun `an idle project with a live chat stays a tile in the connected tone`() {
        val card = projectCardPresentation(project("/repo"), emptyMap(), setOf("agent/s1"))

        assertEquals(ProjectCardVariant.TILE, card.variant)
        assertEquals(CardTone.CONNECTED, card.tone)
    }

    private fun chatStatuses(status: TurnStatus) = mapOf("agent/s1" to TurnActivity(status))

    private fun project(repo: String) =
        ProjectSummary(
            gateway,
            repo,
            listOf(
                ProjectChat(
                    SessionSummary(id = "s1"),
                    LaunchableTarget.GatewayAgent(
                        GatewayAgentBinding(
                            id = "agent",
                            name = "Agent",
                            gatewaySourceId = gateway.id,
                            agentId = "agent",
                        ),
                        gateway,
                    ),
                    null,
                ),
            ),
            null,
            null,
        )
}
