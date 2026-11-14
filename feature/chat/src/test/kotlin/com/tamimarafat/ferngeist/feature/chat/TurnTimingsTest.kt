package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.core.model.TurnTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnTimingsTest {
    @Test
    fun eachTurnsLastReplyIsKeyedByThePromptsPlaceAndText() {
        val messages =
            listOf(prompt("p1", " fix it "), reply("r1"), reply("r2"), prompt("p2", "again"), reply("r3"))

        assertEquals(
            mapOf("r2" to TurnKey(0, "fix it".hashCode()), "r3" to TurnKey(1, "again".hashCode())),
            messages.turnKeys(),
        )
    }

    @Test
    fun aReplayGetsItsStoredTimeBackAndOnlyNewLiveTimesAreRecorded() {
        val live = TurnTiming(1_000L, 5_000L)
        val stored = mapOf(TurnKey(0, "a".hashCode()) to TurnTiming(10L, 20L))
        val messages = listOf(prompt("p1", "a"), reply("r1"), prompt("p2", "b"), reply("r2", live))
        val keys = messages.turnKeys()

        assertEquals(TurnTiming(10L, 20L), messages.withStoredTurns(keys, stored)[1].turn)
        assertEquals(mapOf(TurnKey(1, "b".hashCode()) to live), messages.unstoredTurns(keys, stored))
        // A replay whose prompt differs at that place does not take another turn's time.
        val replayed = listOf(prompt("p1", "other"), reply("r1"))
        assertEquals(null, replayed.withStoredTurns(replayed.turnKeys(), stored)[1].turn)
    }

    private fun prompt(
        id: String,
        text: String,
    ) = ChatMessage(id = id, role = ChatMessage.Role.USER, contentBlocks = listOf(ContentBlock.Text(text)))

    private fun reply(
        id: String,
        turn: TurnTiming? = null,
    ) = ChatMessage(id = id, role = ChatMessage.Role.ASSISTANT, turn = turn)
}
