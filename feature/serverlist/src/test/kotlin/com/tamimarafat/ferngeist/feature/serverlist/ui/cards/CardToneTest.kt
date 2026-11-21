package com.tamimarafat.ferngeist.feature.serverlist.ui.cards

import com.tamimarafat.ferngeist.core.common.ui.status.CardTone
import com.tamimarafat.ferngeist.core.common.ui.status.cardTone
import com.tamimarafat.ferngeist.core.model.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CardToneTest {
    @Test
    fun `a wait or a failure tints its card even while the connection is live`() {
        val asked = listOf(TurnStatus.NEEDS_ATTENTION to CardTone.ATTENTION, TurnStatus.ERROR to CardTone.FAILED)

        for ((status, tone) in asked) {
            assertEquals(tone, cardTone(status, hero = true, live = true, failed = false))
        }
    }

    @Test
    fun `a dead connection only tints a card whose turn asks for nothing`() {
        assertEquals(CardTone.FAILED, cardTone(TurnStatus.WORKING, hero = true, live = false, failed = true))
        assertEquals(
            CardTone.ATTENTION,
            cardTone(TurnStatus.NEEDS_ATTENTION, hero = false, live = false, failed = true),
        )
    }
}
