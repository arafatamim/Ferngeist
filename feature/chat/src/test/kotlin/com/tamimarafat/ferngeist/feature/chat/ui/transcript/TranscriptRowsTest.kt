package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptRowsTest {
    private fun user(
        id: String,
        text: String = "hi",
    ) = ChatMessage(id = id, role = ChatMessage.Role.USER, contentBlocks = listOf(ContentBlock.Text(text)))

    private fun reply(id: String) = ChatMessage(id = id, role = ChatMessage.Role.ASSISTANT)

    private val rows =
        TranscriptRows(
            resumedSession = true,
            hasOlder = true,
            turns = listOf(reply("a0"), user("u1"), reply("a1"), user("u2", text = "")).toTurns(),
        )

    @Test
    fun `turn rows follow the leading rows and the spacer ends the list`() {
        assertEquals("a0", rows.turnAt(2)?.first?.id)
        assertEquals("u1", rows.turnAt(3)?.first?.id)
        assertNull(rows.turnAt(1))
        assertNull(rows.turnAt(5))
        assertEquals(5, rows.endIndex)
    }

    @Test
    fun `a saved reply id resolves to its turn's row`() {
        assertEquals(3, rows.rowOfMessage("a1"))
        assertEquals(2, rows.rowOfMessage("a0"))
        assertNull(rows.rowOfMessage("gone"))
    }

    @Test
    fun `only turns with a prompt to show get a chip row`() {
        assertEquals(listOf(3), rows.promptRows)
    }
}
