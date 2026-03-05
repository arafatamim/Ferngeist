package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRuntimeTest {

    @Test
    fun hydrating_buffers_events_until_complete() = runTest {
        val runtime = SessionRuntime(sessionId = "ses_test")

        runtime.beginHydration()
        assertEquals(SessionLoadState.HYDRATING, runtime.snapshot.value.loadState)
        assertTrue(runtime.snapshot.value.messages.isEmpty())

        runtime.onEvent(AppSessionEvent.UserMessage(text = "he", append = true))
        runtime.onEvent(AppSessionEvent.UserMessage(text = "y", append = true))

        // No partial transcript should leak before hydration completes.
        assertTrue(runtime.snapshot.value.messages.isEmpty())

        runtime.completeHydration()

        val snapshot = runtime.snapshot.value
        assertEquals(SessionLoadState.READY, snapshot.loadState)
        assertEquals(1, snapshot.messages.size)
        assertEquals(ChatMessage.Role.USER, snapshot.messages.first().role)
        assertEquals("hey", snapshot.messages.first().content)
    }

    @Test
    fun large_replay_preserves_all_agent_chunks_in_order() = runTest {
        val runtime = SessionRuntime(sessionId = "ses_test")
        runtime.beginHydration()

        val expected = buildString {
            for (i in 0 until 1200) {
                val chunk = "[$i]"
                append(chunk)
                runtime.onEvent(AppSessionEvent.AgentMessage(chunk))
            }
        }

        runtime.completeHydration()

        val snapshot = runtime.snapshot.value
        assertEquals(SessionLoadState.READY, snapshot.loadState)
        assertEquals(1, snapshot.messages.size)
        val assistant = snapshot.messages.single()
        assertEquals(ChatMessage.Role.ASSISTANT, assistant.role)
        assertEquals(expected, assistant.content)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun interleaved_tool_calls_keep_segment_order_and_updates() = runTest {
        val runtime = SessionRuntime(sessionId = "ses_test")
        runtime.beginHydration()

        runtime.onEvent(AppSessionEvent.AgentMessage("hello"))
        runtime.onEvent(
            AppSessionEvent.ToolCallStarted(
                toolCallId = "tool_1",
                title = "Read",
                kind = "read",
                status = "running"
            )
        )
        runtime.onEvent(AppSessionEvent.AgentMessage(" world"))
        runtime.onEvent(
            AppSessionEvent.ToolCallUpdated(
                toolCallId = "tool_1",
                status = "completed",
                title = null,
                kind = null,
                output = "ok"
            )
        )
        runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))

        runtime.completeHydration()

        val message = runtime.snapshot.value.messages.single()
        val segments = message.segments
        assertEquals(3, segments.size)
        assertEquals(AssistantSegment.Kind.MESSAGE, segments[0].kind)
        assertEquals("hello", segments[0].text)
        assertEquals(AssistantSegment.Kind.TOOL_CALL, segments[1].kind)
        assertEquals(AssistantSegment.Kind.MESSAGE, segments[2].kind)
        assertEquals(" world", segments[2].text)

        val tool = segments[1].toolCall
        assertEquals("completed", tool?.status)
        assertEquals("ok", tool?.output)
        assertFalse(message.isStreaming)
    }

    @Test
    fun post_hydration_events_append_after_committed_history() = runTest {
        val runtime = SessionRuntime(sessionId = "ses_test")
        runtime.beginHydration()
        runtime.onEvent(AppSessionEvent.UserMessage(text = "hey", append = true))
        runtime.completeHydration()

        runtime.onEvent(AppSessionEvent.AgentMessage("hello there"))
        runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))

        val snapshot = runtime.snapshot.value
        assertEquals(2, snapshot.messages.size)
        assertEquals("hey", snapshot.messages[0].content)
        assertEquals("hello there", snapshot.messages[1].content)
        assertEquals(ChatMessage.Role.ASSISTANT, snapshot.messages[1].role)
        assertFalse(snapshot.isStreaming)
    }

    @Test
    fun commands_and_usage_survive_hydration_boundary() = runTest {
        val runtime = SessionRuntime(sessionId = "ses_test")
        runtime.beginHydration()

        runtime.onEvent(AppSessionEvent.CommandsUpdated(listOf("init", "status")))
        runtime.onEvent(AppSessionEvent.UsageUpdated(totalTokens = 99, contextWindowTokens = 4096, costUsd = 0.12))

        runtime.completeHydration()

        val snapshot = runtime.snapshot.value
        assertTrue(snapshot.commandsAdvertised)
        assertEquals(listOf("init", "status"), snapshot.availableCommands)
        assertEquals(99, snapshot.usage?.totalTokens)
        assertEquals(4096, snapshot.usage?.contextWindowTokens)
        assertEquals(0.12, snapshot.usage?.costUsd ?: 0.0, 0.0001)
    }

    @Test
    fun fail_hydration_sets_failed_state_and_error() = runTest {
        val runtime = SessionRuntime(sessionId = "ses_test")
        runtime.beginHydration()
        runtime.onEvent(AppSessionEvent.AgentMessage("partial"))

        runtime.failHydration("load failed")

        val snapshot = runtime.snapshot.value
        assertEquals(SessionLoadState.FAILED, snapshot.loadState)
        assertEquals("load failed", snapshot.error)
        assertFalse(snapshot.isStreaming)
    }
}
