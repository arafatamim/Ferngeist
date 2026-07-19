package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [findTranscriptMatches] and navigation helpers. */
class TranscriptSearcherTest {

    // region: findTranscriptMatches

    @Test
    fun `returns empty list when query is blank`() {
        val messages = listOf(userMessage("hello world"))
        assertEquals(emptyList<TranscriptMatch>(), findTranscriptMatches(messages, ""))
        assertEquals(emptyList<TranscriptMatch>(), findTranscriptMatches(messages, "  "))
    }

    @Test
    fun `returns empty list when query is single character`() {
        val messages = listOf(userMessage("hello world"))
        assertEquals(emptyList<TranscriptMatch>(), findTranscriptMatches(messages, "h"))
    }

    @Test
    fun `returns empty list when messages list is empty`() {
        assertEquals(emptyList<TranscriptMatch>(), findTranscriptMatches(emptyList(), "hello"))
    }

    @Test
    fun `returns empty list when no message contains the query`() {
        val messages = listOf(userMessage("hello world"), userMessage("goodbye"))
        assertEquals(emptyList<TranscriptMatch>(), findTranscriptMatches(messages, "xyz"))
    }

    @Test
    fun `returns empty list when all messages have blank searchable text`() {
        val messages = listOf(
            ChatMessage(id = "1", role = ChatMessage.Role.USER, content = ""),
            ChatMessage(id = "2", role = ChatMessage.Role.ASSISTANT, segments = persistentListOf()),
        )
        assertEquals(emptyList<TranscriptMatch>(), findTranscriptMatches(messages, "test"))
    }

    @Test
    fun `finds single match in a user message`() {
        val messages = listOf(userMessage("Hello World", id = "m1"))
        val matches = findTranscriptMatches(messages, "hello")
        assertEquals(1, matches.size)
        assertEquals(TranscriptMatch(messageId = "m1", messageIndex = 0, matchStart = 0, matchEnd = 5), matches[0])
    }

    @Test
    fun `finds match in the middle of a user message`() {
        val messages = listOf(userMessage("say hello to the world", id = "m1"))
        val matches = findTranscriptMatches(messages, "hello")
        assertEquals(1, matches.size)
        assertEquals(TranscriptMatch(messageId = "m1", messageIndex = 0, matchStart = 4, matchEnd = 9), matches[0])
    }

    @Test
    fun `is case-insensitive`() {
        val messages = listOf(userMessage("HeLLo WoRLd", id = "m1"))
        val matches = findTranscriptMatches(messages, "hello")
        assertEquals(1, matches.size)
        assertEquals(TranscriptMatch(messageId = "m1", messageIndex = 0, matchStart = 0, matchEnd = 5), matches[0])
    }

    @Test
    fun `finds multiple non-overlapping matches in a single message`() {
        val messages = listOf(userMessage("hello and hello again", id = "m1"))
        val matches = findTranscriptMatches(messages, "hello")
        assertEquals(2, matches.size)
        assertEquals(TranscriptMatch(messageId = "m1", messageIndex = 0, matchStart = 0, matchEnd = 5), matches[0])
        assertEquals(TranscriptMatch(messageId = "m1", messageIndex = 0, matchStart = 10, matchEnd = 15), matches[1])
    }

    @Test
    fun `finds matches across multiple messages`() {
        val messages = listOf(
            userMessage("hello there", id = "m1"),
            userMessage("say hello back", id = "m2"),
            userMessage("goodbye", id = "m3"),
        )
        val matches = findTranscriptMatches(messages, "hello")
        assertEquals(2, matches.size)
        assertEquals("m1", matches[0].messageId)
        assertEquals(0, matches[0].messageIndex)
        assertEquals("m2", matches[1].messageId)
        assertEquals(1, matches[1].messageIndex)
    }

    @Test
    fun `finds match in assistant message segments`() {
        val messages = listOf(
            ChatMessage(
                id = "a1",
                role = ChatMessage.Role.ASSISTANT,
                segments = persistentListOf(
                    AssistantSegment(id = "s1", kind = AssistantSegment.Kind.MESSAGE, text = "Here is the plan:"),
                    AssistantSegment(id = "s2", kind = AssistantSegment.Kind.MESSAGE, text = "Refactor the auth module"),
                ),
            ),
        )
        val matches = findTranscriptMatches(messages, "auth")
        // Concatenated text: "Here is the plan: Refactor the auth module"
        // "auth" starts at index 31
        assertEquals(1, matches.size)
        assertEquals("a1", matches[0].messageId)
        assertEquals(31, matches[0].matchStart)
        assertEquals(35, matches[0].matchEnd)
    }

    @Test
    fun `skips messages whose searchable text is blank`() {
        val messages = listOf(
            userMessage("", id = "empty"),
            userMessage("search target", id = "target"),
        )
        val matches = findTranscriptMatches(messages, "search")
        assertEquals(1, matches.size)
        assertEquals("target", matches[0].messageId)
    }

    @Test
    fun `handles query with unicode characters`() {
        val messages = listOf(userMessage("café résumé naïve", id = "m1"))
        val matches = findTranscriptMatches(messages, "résumé")
        assertEquals(1, matches.size)
        assertEquals(TranscriptMatch(messageId = "m1", messageIndex = 0, matchStart = 5, matchEnd = 11), matches[0])
    }

    // endregion

    // region: nextMatchIndex / previousMatchIndex

    @Test
    fun `nextMatchIndex cycles forward with wrap-around`() {
        assertEquals(1, nextMatchIndex(0, 3))
        assertEquals(2, nextMatchIndex(1, 3))
        assertEquals(0, nextMatchIndex(2, 3))
    }

    @Test
    fun `previousMatchIndex cycles backward with wrap-around`() {
        assertEquals(2, previousMatchIndex(0, 3))
        assertEquals(0, previousMatchIndex(1, 3))
        assertEquals(1, previousMatchIndex(2, 3))
    }

    @Test
    fun `nextMatchIndex returns 0 when matchCount is zero`() {
        assertEquals(0, nextMatchIndex(0, 0))
        assertEquals(0, nextMatchIndex(5, 0))
    }

    @Test
    fun `previousMatchIndex returns 0 when matchCount is zero`() {
        assertEquals(0, previousMatchIndex(0, 0))
        assertEquals(0, previousMatchIndex(5, 0))
    }

    @Test
    fun `nextMatchIndex returns 0 when only one match exists`() {
        assertEquals(0, nextMatchIndex(0, 1))
    }

    @Test
    fun `previousMatchIndex returns 0 when only one match exists`() {
        assertEquals(0, previousMatchIndex(0, 1))
    }

    // endregion

    // region: helpers

    private fun userMessage(content: String, id: String = "msg") = ChatMessage(
        id = id,
        role = ChatMessage.Role.USER,
        content = content,
    )


    // endregion
}
