package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import com.tamimarafat.ferngeist.core.common.ui.status.CardTone
import com.tamimarafat.ferngeist.core.common.ui.status.CardVariant
import com.tamimarafat.ferngeist.core.common.ui.status.cardPresentation
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentCardPresentationTest {
    @Test
    fun `a working direct agent remains a connected row`() {
        val card = cardPresentation(listOf(TurnActivity(TurnStatus.WORKING)), live = true, direct = true)

        assertEquals(CardVariant.DIRECT, card.variant)
        assertEquals(CardTone.CONNECTED, card.tone)
    }

    @Test
    fun `attention and error promote gateway agents to wide cards`() {
        for (status in listOf(TurnStatus.NEEDS_ATTENTION, TurnStatus.ERROR)) {
            val card =
                cardPresentation(listOf(TurnActivity(status)), live = false)

            assertEquals(CardVariant.HERO, card.variant)
            assertEquals(status, card.activity.status)
        }
    }

    @Test
    fun `unread finished chat only gives hero a primary tone while connected`() {
        val chats = listOf(TurnActivity(TurnStatus.FINISHED))
        val disconnected = cardPresentation(chats, live = false)
        val connected = cardPresentation(chats, live = true)

        assertEquals(CardTone.DEFAULT, disconnected.tone)
        assertEquals(CardTone.PRIMARY, connected.tone)
    }

    @Test
    fun `error remains the headline when another chat is working`() {
        val card =
            cardPresentation(
                chats = listOf(TurnActivity(TurnStatus.WORKING), TurnActivity(TurnStatus.ERROR)),
                live = false,
            )
        assertEquals(CardVariant.HERO, card.variant)
        assertEquals(TurnStatus.ERROR, card.activity.status)
    }
}
