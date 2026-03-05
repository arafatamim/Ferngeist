package com.tamimarafat.ferngeist.feature.chat.state

import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMessageReducer
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class SessionMessageReducerTest {

    @Test
    fun `tool call update falls back to rawOutput when output missing`() {
        val started = SessionMessageReducer.handleEvent(
            emptyList(),
            AppSessionEvent.ToolCallStarted(
                toolCallId = "tool_1",
                title = "Read",
                kind = "read",
                status = "running"
            )
        )

        val updated = SessionMessageReducer.handleEvent(
            started,
            AppSessionEvent.ToolCallUpdated(
                toolCallId = "tool_1",
                status = "completed",
                title = null,
                kind = null,
                output = null,
                rawOutput = "{\"ok\":true}"
            )
        )

        val toolCall = updated.last().segments
            .first { it.kind == AssistantSegment.Kind.TOOL_CALL }
            .toolCall
        assertNotNull(toolCall)
        assertEquals("{\"ok\":true}", toolCall?.output)
    }

    @Test
    fun `tool call started is deduplicated for replayed ids`() {
        val first = SessionMessageReducer.handleEvent(
            emptyList(),
            AppSessionEvent.ToolCallStarted(
                toolCallId = "tool_replay",
                title = "Replay",
                kind = "read",
                status = "running"
            )
        )
        val second = SessionMessageReducer.handleEvent(
            first,
            AppSessionEvent.ToolCallStarted(
                toolCallId = "tool_replay",
                title = "Replay",
                kind = "read",
                status = "running"
            )
        )

        val toolCallCount = second.last().segments.count {
            it.kind == AssistantSegment.Kind.TOOL_CALL &&
                it.toolCall?.toolCallId == "tool_replay"
        }
        assertEquals(1, toolCallCount)
    }

    @Test
    fun `new user message closes leaked assistant streaming bubble`() {
        val leaked = listOf(
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                isStreaming = true,
                content = "partial"
            )
        )

        val updated = SessionMessageReducer.handleEvent(
            leaked,
            AppSessionEvent.UserMessage("next prompt")
        )

        assertFalse(updated.first().isStreaming)
        assertEquals(ChatMessage.Role.USER, updated.last().role)
        assertEquals("next prompt", updated.last().content)
    }

    @Test
    fun `user message chunks append into a single user bubble`() {
        val first = SessionMessageReducer.handleEvent(
            emptyList(),
            AppSessionEvent.UserMessage(text = "he", append = true)
        )
        val second = SessionMessageReducer.handleEvent(
            first,
            AppSessionEvent.UserMessage(text = "y", append = true)
        )

        assertEquals(1, second.size)
        assertEquals(ChatMessage.Role.USER, second.last().role)
        assertEquals("hey", second.last().content)
    }

    @Test
    fun `echoed user chunk is ignored when assistant placeholder is already streaming`() {
        val withUser = SessionMessageReducer.handleEvent(
            emptyList(),
            AppSessionEvent.UserMessage("hello")
        )
        val withAssistantPlaceholder = SessionMessageReducer.startStreaming(withUser)

        val updated = SessionMessageReducer.handleEvent(
            withAssistantPlaceholder,
            AppSessionEvent.UserMessage(text = "hello", append = true)
        )

        val userMessageCount = updated.count { it.role == ChatMessage.Role.USER }
        assertEquals(1, userMessageCount)
        assertEquals(2, updated.size)
    }

    @Test
    fun `turn complete closes most recent streaming message`() {
        val withStream = SessionMessageReducer.startStreaming(
            listOf(
                ChatMessage(role = ChatMessage.Role.USER, content = "hello")
            )
        )

        val completed = SessionMessageReducer.handleEvent(
            withStream,
            AppSessionEvent.TurnComplete("end_turn")
        )

        assertFalse(completed.last().isStreaming)
    }

    @Test
    fun `agent chunks continue appending after turn complete if no new user turn started`() {
        val chunk1 = SessionMessageReducer.handleEvent(
            emptyList(),
            AppSessionEvent.AgentMessage("how")
        )
        val chunk2 = SessionMessageReducer.handleEvent(
            chunk1,
            AppSessionEvent.AgentMessage(" are")
        )
        val completed = SessionMessageReducer.handleEvent(
            chunk2,
            AppSessionEvent.TurnComplete("end_turn")
        )
        val resumedChunk = SessionMessageReducer.handleEvent(
            completed,
            AppSessionEvent.AgentMessage(" you?")
        )

        assertEquals(1, resumedChunk.size)
        assertEquals("how are you?", resumedChunk.last().content)
    }

    @Test
    fun `message chunks append to existing message segment even if tool update was interleaved`() {
        val first = SessionMessageReducer.handleEvent(
            emptyList(),
            AppSessionEvent.AgentMessage("hello")
        )
        val withTool = SessionMessageReducer.handleEvent(
            first,
            AppSessionEvent.ToolCallStarted(
                toolCallId = "tool_1",
                title = "Read",
                kind = "read",
                status = "running"
            )
        )
        val second = SessionMessageReducer.handleEvent(
            withTool,
            AppSessionEvent.AgentMessage(" world")
        )

        assertEquals("hello world", second.last().content)
    }

    @Test
    fun `message segment order follows server event order when tool call is interleaved`() {
        val first = SessionMessageReducer.handleEvent(
            emptyList(),
            AppSessionEvent.AgentMessage("hello")
        )
        val withTool = SessionMessageReducer.handleEvent(
            first,
            AppSessionEvent.ToolCallStarted(
                toolCallId = "tool_order",
                title = "Read",
                kind = "read",
                status = "running"
            )
        )
        val second = SessionMessageReducer.handleEvent(
            withTool,
            AppSessionEvent.AgentMessage(" world")
        )

        val segments = second.last().segments
        assertEquals(3, segments.size)
        assertEquals(AssistantSegment.Kind.MESSAGE, segments[0].kind)
        assertEquals("hello", segments[0].text)
        assertEquals(AssistantSegment.Kind.TOOL_CALL, segments[1].kind)
        assertEquals(AssistantSegment.Kind.MESSAGE, segments[2].kind)
        assertEquals(" world", segments[2].text)
    }
}
