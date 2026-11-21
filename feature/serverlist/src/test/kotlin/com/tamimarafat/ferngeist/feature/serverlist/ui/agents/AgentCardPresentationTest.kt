package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.CardTone
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentCardPresentationTest {
    @Test
    fun `attention and error promote gateway agents to wide cards`() {
        for (status in listOf(TurnStatus.NEEDS_ATTENTION, TurnStatus.ERROR)) {
            val card =
                agentCardPresentation(true, listOf(TurnActivity(status)), ServerConnectionUiState(false, false, false))

            assertEquals(AgentCardVariant.HERO, card.variant)
            assertEquals(status, card.status)
        }
    }

    @Test
    fun `unread finished chat only gives hero a primary tone while connected`() {
        val chats = listOf(TurnActivity(TurnStatus.FINISHED))
        val disconnected = agentCardPresentation(true, chats, ServerConnectionUiState(false, false, false))
        val connected = agentCardPresentation(true, chats, ServerConnectionUiState(false, true, false))

        assertEquals(CardTone.DEFAULT, disconnected.tone)
        assertEquals(CardTone.PRIMARY, connected.tone)
    }

    @Test
    fun `error remains the headline when another chat is working`() {
        val card =
            agentCardPresentation(
                gatewayAgent = true,
                chats = listOf(TurnActivity(TurnStatus.WORKING), TurnActivity(TurnStatus.ERROR)),
                connection = ServerConnectionUiState(false, false, false),
            )
        assertEquals(AgentCardVariant.HERO, card.variant)
        assertEquals(TurnStatus.ERROR, card.status)
    }
}
