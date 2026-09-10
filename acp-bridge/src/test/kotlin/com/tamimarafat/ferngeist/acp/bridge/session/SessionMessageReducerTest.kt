package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessageReducerTest {
    @Test
    fun finishStreaming_singleTrailingBubble_clearsFlag() {
        val messages =
            listOf(
                assistantMessage(id = "assistant", content = "hello", isStreaming = true),
                userMessage(id = "user", content = "hi"),
            )

        val result = SessionMessageReducer.finishStreaming(messages)

        assertTrue("no message may remain streaming", result.none { it.isStreaming })
    }

    @Test
    fun finishStreaming_severalBubblesIncludingNonLast_clearsAll() {
        val messages =
            listOf(
                assistantMessage(id = "a", content = "one", isStreaming = true),
                userMessage(id = "b", content = "two"),
                assistantMessage(id = "c", content = "three", isStreaming = true),
            )

        val result = SessionMessageReducer.finishStreaming(messages)

        assertTrue("every streaming bubble must be cleared, not just the last", result.none { it.isStreaming })
        assertEquals(3, result.size)
    }

    @Test
    fun finishStreaming_nothingStreaming_returnsSameInstance() {
        val messages =
            listOf(
                userMessage(id = "user", content = "hi"),
                assistantMessage(id = "assistant", content = "hello"),
            )

        val result = SessionMessageReducer.finishStreaming(messages)

        assertSame("a list with nothing to clear must not be copied", messages, result)
    }

    @Test
    fun finishStreaming_nonStreamingMessages_surviveUnchangedAndInOrder() {
        val userSegment = AssistantSegment(id = "seg_u", text = "user segment")
        val assistantSegment = AssistantSegment(id = "seg_a", text = "assistant segment")
        val messages =
            listOf(
                userMessage(id = "user", content = "question", segments = persistentListOf(userSegment)),
                assistantMessage(
                    id = "assistant",
                    content = "answer",
                    segments = persistentListOf(assistantSegment),
                    isStreaming = true,
                ),
                ChatMessage(id = "system", role = ChatMessage.Role.SYSTEM, content = "note"),
            )

        val result = SessionMessageReducer.finishStreaming(messages)

        assertEquals(listOf("user", "assistant", "system"), result.map { it.id })
        assertEquals(ChatMessage.Role.USER, result[0].role)
        assertEquals("question", result[0].content)
        assertEquals(listOf(userSegment), result[0].segments.toList())
        assertEquals(ChatMessage.Role.ASSISTANT, result[1].role)
        assertEquals("answer", result[1].content)
        assertEquals(listOf(assistantSegment), result[1].segments.toList())
        assertFalse(result[1].isStreaming)
        assertEquals(ChatMessage.Role.SYSTEM, result[2].role)
        assertEquals("note", result[2].content)
    }

    private fun userMessage(
        id: String,
        content: String,
        segments: PersistentList<AssistantSegment> = persistentListOf(),
    ) = ChatMessage(
        id = id,
        role = ChatMessage.Role.USER,
        content = content,
        segments = segments,
    )

    private fun assistantMessage(
        id: String,
        content: String,
        segments: PersistentList<AssistantSegment> = persistentListOf(),
        isStreaming: Boolean = false,
    ) = ChatMessage(
        id = id,
        role = ChatMessage.Role.ASSISTANT,
        content = content,
        segments = segments,
        isStreaming = isStreaming,
    )
}
