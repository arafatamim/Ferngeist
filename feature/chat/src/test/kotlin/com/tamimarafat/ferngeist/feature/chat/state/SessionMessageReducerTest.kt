package com.tamimarafat.ferngeist.feature.chat.state

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMessageReducer
import com.tamimarafat.ferngeist.acp.bridge.session.ToolCallLocation
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMessageReducerTest {
    /**
     * Thread the (messages, tool-call index) pair through a single event. Most tests don't care
     * about the returned index, but the helpers keep every call correct without inlining the
     * pair-destructuring at every site.
     */
    private fun apply(
        messages: List<ChatMessage>,
        index: Map<String, ToolCallLocation>,
        event: AppSessionEvent,
    ): Pair<List<ChatMessage>, Map<String, ToolCallLocation>> {
        val r = SessionMessageReducer.handleEvent(messages, index, event)
        return r.messages to r.toolCallIndex
    }

    /** Builds a [PlanEntry] with the given content, priority, and status. */
    private fun planEntry(
        content: String,
        priority: PlanEntryPriority = PlanEntryPriority.HIGH,
        status: PlanEntryStatus = PlanEntryStatus.PENDING,
    ) = PlanEntry(content = content, priority = priority, status = status)

    /** Applies a [AppSessionEvent.PlanUpdated] event and returns the updated messages. */
    private fun applyPlan(
        messages: List<ChatMessage>,
        vararg entries: PlanEntry,
    ): List<ChatMessage> =
        apply(messages, emptyMap(), AppSessionEvent.PlanUpdated(entries = entries.toList()))
            .first

    /** Asserts that the last assistant message has a single PLAN segment with the expected entry count. */
    private fun assertPlanSegment(
        messages: List<ChatMessage>,
        expectedEntryCount: Int,
    ) {
        assertEquals(1, messages.last().segments.size)
        assertEquals(
            AssistantSegment.Kind.PLAN,
            messages
                .last()
                .segments
                .last()
                .kind,
        )
        assertEquals(
            expectedEntryCount,
            messages
                .last()
                .segments
                .last()
                .planEntries
                ?.size,
        )
    }

    /** Asserts the status of the first plan entry in the last assistant message. */
    private fun assertFirstPlanEntryStatus(
        messages: List<ChatMessage>,
        expected: PlanEntryStatus,
    ) {
        assertEquals(
            expected,
            messages
                .last()
                .segments
                .last()
                .planEntries
                ?.get(0)
                ?.status,
        )
    }

    @Test
    fun `tool call update falls back to rawOutput when output missing`() {
        val (started, startedIdx) =
            apply(
                emptyList(),
                emptyMap(),
                AppSessionEvent.ToolCallStarted(
                    toolCallId = "tool_1",
                    title = "Read",
                    kind = ToolKind.READ,
                    status = ToolCallStatus.IN_PROGRESS,
                ),
            )

        val (updated, _) =
            apply(
                started,
                startedIdx,
                AppSessionEvent.ToolCallUpdated(
                    toolCallId = "tool_1",
                    status = ToolCallStatus.COMPLETED,
                    title = null,
                    kind = null,
                    content = null,
                    rawOutput = JsonPrimitive("{\"ok\":true}"),
                ),
            )

        val toolCall =
            updated
                .last()
                .segments
                .first { it.kind == AssistantSegment.Kind.TOOL_CALL }
                .toolCall
        assertNotNull(toolCall)
        assertNull(toolCall?.content)
        assertEquals(JsonPrimitive("{\"ok\":true}"), toolCall?.rawOutput)
    }

    @Test
    fun `tool call started is deduplicated for replayed ids`() {
        val (first, firstIdx) =
            apply(
                emptyList(),
                emptyMap(),
                AppSessionEvent.ToolCallStarted(
                    toolCallId = "tool_replay",
                    title = "Replay",
                    kind = ToolKind.READ,
                    status = ToolCallStatus.IN_PROGRESS,
                ),
            )
        val (second, _) =
            apply(
                first,
                firstIdx,
                AppSessionEvent.ToolCallStarted(
                    toolCallId = "tool_replay",
                    title = "Replay",
                    kind = ToolKind.READ,
                    status = ToolCallStatus.IN_PROGRESS,
                ),
            )

        val toolCallCount =
            second.last().segments.count {
                it.kind == AssistantSegment.Kind.TOOL_CALL &&
                    it.toolCall?.toolCallId == "tool_replay"
            }
        assertEquals(1, toolCallCount)
    }

    @Test
    fun `new user message closes leaked assistant streaming bubble`() {
        val leaked =
            listOf(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    isStreaming = true,
                    content = "partial",
                ),
            )

        val (updated, _) =
            apply(
                leaked,
                emptyMap(),
                AppSessionEvent.UserMessage("next prompt"),
            )

        assertFalse(updated.first().isStreaming)
        assertEquals(ChatMessage.Role.USER, updated.last().role)
        assertEquals("next prompt", updated.last().content)
    }

    @Test
    fun `echoed user chunk is ignored when assistant placeholder is already streaming`() {
        val (withUser, _) =
            apply(
                emptyList(),
                emptyMap(),
                AppSessionEvent.UserMessage("hello"),
            )
        val withAssistantPlaceholder = SessionMessageReducer.startStreaming(withUser)

        val (updated, _) =
            apply(
                withAssistantPlaceholder,
                emptyMap(),
                AppSessionEvent.UserMessage(text = "hello", append = true),
            )

        val userMessageCount = updated.count { it.role == ChatMessage.Role.USER }
        assertEquals(1, userMessageCount)
        assertEquals(2, updated.size)
    }

    @Test
    fun `agent chunks continue appending after turn complete if no new user turn started`() {
        val (chunk1, _) =
            apply(
                emptyList(),
                emptyMap(),
                AppSessionEvent.AgentMessage("how"),
            )
        val (chunk2, _) =
            apply(
                chunk1,
                emptyMap(),
                AppSessionEvent.AgentMessage(" are"),
            )
        val (completed, _) =
            apply(
                chunk2,
                emptyMap(),
                AppSessionEvent.TurnComplete("end_turn"),
            )
        val (resumedChunk, _) =
            apply(
                completed,
                emptyMap(),
                AppSessionEvent.AgentMessage(" you?"),
            )
        // The flat `content` is derived when a turn closes, so close the turn before asserting it.
        val (finished, _) =
            apply(
                resumedChunk,
                emptyMap(),
                AppSessionEvent.TurnComplete("end_turn"),
            )

        assertEquals(1, finished.size)
        assertEquals("how are you?", finished.last().content)
    }

    @Test
    fun `message chunks after a tool call append at the end in server order`() {
        val (first, _) =
            apply(
                emptyList(),
                emptyMap(),
                AppSessionEvent.AgentMessage("hello"),
            )
        val (withTool, _) =
            apply(
                first,
                emptyMap(),
                AppSessionEvent.ToolCallStarted(
                    toolCallId = "tool_order",
                    title = "Read",
                    kind = ToolKind.READ,
                    status = ToolCallStatus.IN_PROGRESS,
                ),
            )
        val (second, _) =
            apply(
                withTool,
                emptyMap(),
                AppSessionEvent.AgentMessage(" world"),
            )

        val segments = second.last().segments
        assertEquals(3, segments.size)
        assertEquals(AssistantSegment.Kind.MESSAGE, segments[0].kind)
        assertEquals("hello", segments[0].text)
        assertEquals(AssistantSegment.Kind.TOOL_CALL, segments[1].kind)
        assertEquals(AssistantSegment.Kind.MESSAGE, segments[2].kind)
        assertEquals(" world", segments[2].text)
    }

    @Test
    fun `plan entries replace existing plan segment on each update`() {
        val first =
            applyPlan(
                emptyList(),
                planEntry(content = "Step 1", priority = PlanEntryPriority.HIGH, status = PlanEntryStatus.PENDING),
            )

        assertPlanSegment(first, expectedEntryCount = 1)

        val second =
            applyPlan(
                first,
                planEntry(content = "Step 1", priority = PlanEntryPriority.HIGH, status = PlanEntryStatus.IN_PROGRESS),
                planEntry(content = "Step 2", priority = PlanEntryPriority.MEDIUM, status = PlanEntryStatus.PENDING),
            )

        assertPlanSegment(second, expectedEntryCount = 2)
        assertFirstPlanEntryStatus(second, PlanEntryStatus.IN_PROGRESS)
    }

    @Test
    fun `append=false user message is deduplicated when assistant streaming placeholder follows`() {
        val (withUser, _) =
            apply(
                emptyList(),
                emptyMap(),
                AppSessionEvent.UserMessage("hello"),
            )
        val withAssistantPlaceholder = SessionMessageReducer.startStreaming(withUser)

        val (updated, _) =
            apply(
                withAssistantPlaceholder,
                emptyMap(),
                AppSessionEvent.UserMessage(text = "hello", append = false),
            )

        val userMessageCount = updated.count { it.role == ChatMessage.Role.USER }
        assertEquals(1, userMessageCount)
    }

    @Test
    fun `a diff sent with the start of a call survives an update that brings only text`() {
        val diff = ToolCallContent.Diff(path = "a.kt", oldText = "a", newText = "b")
        val (started, idx) = apply(emptyList(), emptyMap(), started("t1", content = listOf(diff)))
        val (updated, _) =
            apply(
                started,
                idx,
                AppSessionEvent.ToolCallUpdated(
                    toolCallId = "t1",
                    status = ToolCallStatus.COMPLETED,
                    title = null,
                    kind = null,
                    content = listOf(ToolCallContent.Terminal("term")),
                ),
            )

        assertEquals(
            listOf(diff, ToolCallContent.Terminal("term")),
            updated
                .last()
                .segments
                .single()
                .toolCall
                ?.content,
        )
    }

    @Test
    fun `an id reused in a later turn starts a new call and leaves the old one alone`() {
        val (firstTurn, idx) = apply(emptyList(), emptyMap(), started("t1", title = "Edit old.kt"))
        val messages =
            listOf(
                AppSessionEvent.UserMessage("next prompt"),
                started("t1", title = "Edit new.kt"),
                AppSessionEvent.ToolCallUpdated("t1", ToolCallStatus.COMPLETED, title = null, kind = null),
            ).fold(SessionMessageReducer.finishStreaming(firstTurn) to idx) { (m, i), event -> apply(m, i, event) }
                .first

        val calls = messages.flatMap { it.segments }.mapNotNull { it.toolCall }
        assertEquals(listOf("Edit old.kt", "Edit new.kt"), calls.map { it.title })
        assertEquals(listOf(null, ToolCallStatus.COMPLETED), calls.map { it.status })
    }

    private fun started(
        id: String,
        title: String = "Edit",
        content: List<ToolCallContent>? = null,
    ) = AppSessionEvent.ToolCallStarted(
        toolCallId = id,
        title = title,
        kind = ToolKind.EDIT,
        status = null,
        content = content,
    )
}
