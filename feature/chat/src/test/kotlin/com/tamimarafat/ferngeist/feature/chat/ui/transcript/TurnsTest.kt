package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnsTest {
    @Test
    fun `a prompt and the replies after it are one turn`() {
        val turns = listOf(user("u1"), reply("a1"), user("u2"), reply("a2"), reply("a3")).toTurns()

        assertEquals(
            listOf("u1" to listOf("a1"), "u2" to listOf("a2", "a3")),
            turns.map { it.prompt?.id to it.replies.map(ChatMessage::id) },
        )
        assertEquals("u1", turns.first().first.id)
    }

    @Test
    fun `replies before any prompt are a turn with no prompt`() {
        val turns = listOf(reply("a0"), user("u1")).toTurns()

        assertEquals(
            listOf(null to listOf("a0"), "u1" to emptyList<String>()),
            turns.map { it.prompt?.id to it.replies.map(ChatMessage::id) },
        )
        assertEquals("a0", turns.first().first.id)
    }

    @Test
    fun `prompts in a row are separate turns`() {
        val turns = listOf(user("u1"), user("u2")).toTurns()

        assertEquals(listOf("u1", "u2"), turns.map { it.first.id })
        assertEquals(listOf(emptyList<ChatMessage>(), emptyList<ChatMessage>()), turns.map { it.replies })
    }

    @Test
    fun `a window never opens inside a turn`() {
        val messages = listOf(user("u1"), reply("a1"), reply("a2"), user("u2"), reply("a3"))

        assertEquals(0, messages.turnStart(2))
        assertEquals(3, messages.turnStart(3))
        assertEquals(3, messages.turnStart(4))
        assertEquals(0, listOf(reply("a0"), reply("a1")).turnStart(1))
    }

    @Test
    fun `non-user messages stay in the current turn`() {
        val system = ChatMessage(id = "s1", role = ChatMessage.Role.SYSTEM)
        val messages = listOf(user("u1"), system, reply("a1"))
        val turn = messages.toTurns().single()

        assertEquals(listOf("s1", "a1"), turn.replies.map { it.id })
        assertEquals(0, messages.turnStart(2))
    }

    @Test
    fun `turn segments include every reply in order but not the prompt`() {
        val prompt = user("u1").copy(segments = persistentListOf(AssistantSegment(id = "prompt")))
        val first =
            reply("a1").copy(
                segments = persistentListOf(AssistantSegment(id = "s1"), AssistantSegment(id = "s2")),
            )
        val last = reply("a3").copy(segments = persistentListOf(AssistantSegment(id = "s3")))
        val turn = listOf(prompt, first, reply("a2"), last).toTurns().single()

        assertEquals(listOf("s1", "s2", "s3"), turn.segments.map { it.id })
    }

    @Test
    fun `an empty history has no turns`() {
        assertEquals(emptyList<Turn>(), emptyList<ChatMessage>().toTurns())
    }

    private fun user(id: String) = ChatMessage(id = id, role = ChatMessage.Role.USER)

    private fun reply(id: String) = ChatMessage(id = id, role = ChatMessage.Role.ASSISTANT)
}
