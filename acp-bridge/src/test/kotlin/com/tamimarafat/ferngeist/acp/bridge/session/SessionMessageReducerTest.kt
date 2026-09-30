package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
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

    /**
     * Streamed chunks land in `segments`; the flat `content` field is derived from them once a
     * turn closes. Removing that derivation leaves a finalized bubble rendering empty wherever a
     * reader falls back to `content`, so the round trip is asserted end to end.
     */
    @Test
    fun finishStreaming_derivesContentFromEveryChunkInOrder() {
        val chunks = (0 until 200).map { "chunk $it " }
        var messages: List<ChatMessage> = emptyList()
        chunks.forEach { chunk -> messages = appendAgentChunk(messages, chunk) }

        val finished = SessionMessageReducer.finishStreaming(messages)
        val message = finished.single()

        assertEquals(
            "every chunk must survive, in order, in the derived content",
            chunks.joinToString(""),
            message.content,
        )
        assertFalse("the finalized bubble must not stay streaming", message.isStreaming)
    }

    /**
     * A settled reply must be ONE markdown document, not one per chunk. Markdown is parsed per
     * segment, so a chunk boundary inside a construct (here a fenced code block) would leave both
     * halves unterminated and rendered as literal text - the normal case for token streaming.
     */
    @Test
    fun finishStreaming_settlesAReplySplitAcrossAConstructIntoOneSegment() {
        val firstChunk = "Here is code:\n```kotlin\nval x ="
        val secondChunk = "1\n```"
        var messages: List<ChatMessage> = emptyList()
        messages = appendAgentChunk(messages, firstChunk)
        messages = appendAgentChunk(messages, secondChunk)
        val streaming = messages.single()
        val firstSegment = streaming.segments.first()

        val message = SessionMessageReducer.finishStreaming(messages).single()

        assertEquals(
            "a settled reply must be one markdown document, not one per chunk",
            1,
            message.segments.size,
        )
        assertEquals(firstChunk + secondChunk, message.segments.single().text)
        assertEquals(firstChunk + secondChunk, message.content)
        assertEquals(
            "the merged segment must keep the first chunk's id so markdown state keys stay stable",
            firstSegment.id,
            message.segments.single().id,
        )
    }

    /**
     * Only ADJACENT MESSAGE segments merge. A thought that lands between two runs of reply chunks
     * keeps its place and each run folds on its own; absorbing across it would reorder the reply.
     */
    @Test
    fun finishStreaming_mergesEachMessageRunWithoutCrossingAThought() {
        var messages: List<ChatMessage> = emptyList()
        messages = appendAgentChunk(messages, "one ")
        messages = appendAgentChunk(messages, "two")
        messages = appendThoughtChunk(messages, "thinking")
        messages = appendAgentChunk(messages, "three ")
        messages = appendAgentChunk(messages, "four")

        val message = SessionMessageReducer.finishStreaming(messages).single()

        assertEquals(
            listOf(
                AssistantSegment.Kind.MESSAGE,
                AssistantSegment.Kind.THOUGHT,
                AssistantSegment.Kind.MESSAGE,
            ),
            message.segments.map { it.kind },
        )
        assertEquals("one two", message.segments[0].text)
        assertEquals("thinking", message.segments[1].text)
        assertEquals("three four", message.segments[2].text)
        assertEquals("one twothree four", message.content)
    }

    /**
     * Folding a run shortens the segment list, which moves every tool call that follows it. The
     * tool-call index is a cache, so an update arriving after the fold must still land on the tool
     * call's own segment instead of whatever now sits at the recorded index.
     */
    @Test
    fun toolCallUpdateAfterAMergeStillTargetsTheToolCallSegment() {
        var messages: List<ChatMessage> = emptyList()
        var index: Map<String, ToolCallLocation> = emptyMap()
        listOf("let me ", "look").forEach { chunk ->
            val appended =
                SessionMessageReducer.handleEvent(messages, index, AppSessionEvent.AgentMessage(chunk))
            messages = appended.messages
            index = appended.toolCallIndex
        }
        val started =
            SessionMessageReducer.handleEvent(
                messages,
                index,
                AppSessionEvent.ToolCallStarted(
                    toolCallId = "tool_1",
                    title = "Read",
                    kind = ToolKind.READ,
                    status = ToolCallStatus.IN_PROGRESS,
                ),
            )
        assertEquals(
            "the tool call must be recorded behind both chunks",
            ToolCallLocation(messageIndex = 0, segmentIndex = 2),
            started.toolCallIndex["tool_1"],
        )

        val closed =
            SessionMessageReducer.handleEvent(
                started.messages,
                started.toolCallIndex,
                AppSessionEvent.TurnComplete("end_turn"),
            )
        val closedSegments = closed.messages.single().segments
        assertEquals(2, closedSegments.size)

        val updated =
            SessionMessageReducer.handleEvent(
                closed.messages,
                closed.toolCallIndex,
                AppSessionEvent.ToolCallUpdated(
                    toolCallId = "tool_1",
                    status = ToolCallStatus.COMPLETED,
                    title = null,
                    kind = null,
                ),
            )

        val segments = updated.messages.single().segments
        assertEquals(AssistantSegment.Kind.MESSAGE, segments[0].kind)
        assertEquals("let me look", segments[0].text)
        assertEquals(AssistantSegment.Kind.TOOL_CALL, segments[1].kind)
        assertEquals(ToolCallStatus.COMPLETED, segments[1].toolCall?.status)
        assertEquals(
            "the stale index entry must be repaired to the merged layout",
            ToolCallLocation(messageIndex = 0, segmentIndex = 1),
            updated.toolCallIndex["tool_1"],
        )
    }

    /**
     * Cost guard, not a correctness test: merging each chunk into the trailing segment's text
     * copies the whole accumulated bubble, which is O(n^2) bytes over a replay. Appending a
     * fresh segment is O(log n) on the persistent list and copies no text.
     */
    @Test
    fun appendingToLargeBubbleStaysCheap() {
        var messages: List<ChatMessage> = emptyList()
        // Warm up so the measurement below measures the reducer, not JIT compilation.
        repeat(2_000) { i -> messages = appendAgentChunk(messages, "warm $i") }
        repeat(20_000) { i -> messages = appendAgentChunk(messages, "grow $i " + "x".repeat(200)) }

        val start = System.nanoTime()
        repeat(2_000) { i -> messages = appendAgentChunk(messages, "tail $i") }
        val perChunkUs = (System.nanoTime() - start) / 1_000 / 2_000

        println("PERF appendPerChunkUs=$perChunkUs")
        assertTrue(
            "append cost $perChunkUs us suggests the accumulated bubble is rebuilt per chunk",
            perChunkUs < 250,
        )
    }

    /**
     * `content` is left empty while streaming, so the echo-dedup guard must keep depending on
     * `segments.isEmpty()`: a bubble that already carries agent output is not a placeholder and
     * must not swallow the echoed user chunk.
     */
    @Test
    fun placeholderWithAgentSegments_isNotTreatedAsAnEchoTarget() {
        val withUser =
            SessionMessageReducer
                .handleEvent(emptyList(), emptyMap(), AppSessionEvent.UserMessage("hello"))
                .messages
        val withPlaceholder = SessionMessageReducer.startStreaming(withUser)
        val withChunk =
            SessionMessageReducer
                .handleEvent(withPlaceholder, emptyMap(), AppSessionEvent.AgentMessage("real answer"))
                .messages

        val echoed =
            SessionMessageReducer
                .handleEvent(
                    withChunk,
                    emptyMap(),
                    AppSessionEvent.UserMessage(text = "hello", append = true),
                ).messages

        assertEquals("the echoed user chunk must be appended, not deduped away", 3, echoed.size)
        assertEquals(ChatMessage.Role.USER, echoed.last().role)
        assertEquals("hello", echoed.last().content)
    }

    /** Threads one agent chunk through the real reducer rather than reimplementing it here. */
    private fun appendAgentChunk(
        messages: List<ChatMessage>,
        text: String,
    ): List<ChatMessage> =
        SessionMessageReducer
            .handleEvent(messages, emptyMap(), AppSessionEvent.AgentMessage(text))
            .messages

    /** Threads one agent thought through the real reducer rather than reimplementing it here. */
    private fun appendThoughtChunk(
        messages: List<ChatMessage>,
        text: String,
    ): List<ChatMessage> =
        SessionMessageReducer
            .handleEvent(messages, emptyMap(), AppSessionEvent.AgentThought(text))
            .messages

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
