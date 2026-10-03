package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRunsTest {
    @Test
    fun adjacentMessageChunksJoinIntoOneRun() {
        val runs = chunks("Hello", " ", "world").messageRuns()

        assertEquals(1, runs.size)
        assertEquals("Hello world", runs.single().text)
    }

    @Test
    fun aRunKeysOnItsFirstSegmentSoTheKeySurvivesGrowth() {
        val runs = chunks("a", "b", "c").messageRuns()

        assertEquals("s0", runs.single().key)
    }

    @Test
    fun growingTheRunKeepsTheSameKey() {
        // The whole point: chunk 2 must not change the run's identity, or Compose recreates the
        // node and the streaming text resets every chunk.
        val first = chunks("a").messageRuns().single()
        val third = chunks("a", "b", "c").messageRuns().single()

        assertEquals(first.key, third.key)
    }

    @Test
    fun adjacentThoughtChunksJoinIntoOneRun() {
        val segments =
            persistentListOf(
                segment("t0", AssistantSegment.Kind.THOUGHT, "let me "),
                segment("t1", AssistantSegment.Kind.THOUGHT, "look"),
            )
        val runs = segments.thoughtRuns()

        assertEquals(1, runs.size)
        assertEquals("let me look", runs.single().text)
        assertEquals(AssistantSegment.Kind.THOUGHT, runs.single().kind)
    }

    @Test
    fun messageRunsIgnoreThoughtSegmentsSoReasoningIsNotAbsorbedIntoProse() {
        val segments =
            persistentListOf(
                segment("m0", AssistantSegment.Kind.MESSAGE, "before"),
                segment("t0", AssistantSegment.Kind.THOUGHT, "thinking"),
                segment("m1", AssistantSegment.Kind.MESSAGE, "after"),
            )
        val runs = segments.messageRuns()

        assertEquals(2, runs.size)
        assertEquals("before", runs[0].text)
        assertEquals("after", runs[1].text)
    }

    @Test
    fun anEmptyListHasNoRuns() {
        assertEquals(emptyList<SegmentBlock.Run>(), persistentListOf<AssistantSegment>().messageRuns())
    }

    @Test
    fun displayBlocksKeepReasoningProseAndToolCallsInEmittedOrder() {
        val segments =
            listOf(
                segment("m0", AssistantSegment.Kind.MESSAGE, "before"),
                segment("t0", AssistantSegment.Kind.THOUGHT, "think"),
                segment("c0", AssistantSegment.Kind.TOOL_CALL, "call"),
                segment("m1", AssistantSegment.Kind.MESSAGE, "after"),
            )
        val blocks = segments.displayBlocks()

        // Grouping must not reorder: collecting thoughts and prose in separate passes would hoist
        // the reasoning bubble above the prose that preceded it.
        assertEquals(listOf("m0", "t0", "c0", "m1"), blocks.map { it.key })
    }

    @Test
    fun displayBlocksJoinRunsOfBothKindsAndLeaveToolCallsAlone() {
        val segments =
            listOf(
                segment("m0", AssistantSegment.Kind.MESSAGE, "one "),
                segment("m1", AssistantSegment.Kind.MESSAGE, "two"),
                segment("t0", AssistantSegment.Kind.THOUGHT, "why"),
                segment("c0", AssistantSegment.Kind.TOOL_CALL, "call"),
            )
        val blocks = segments.displayBlocks()

        assertEquals(3, blocks.size)
        assertEquals("one two", (blocks[0] as SegmentBlock.Run).text)
        assertEquals(AssistantSegment.Kind.THOUGHT, (blocks[1] as SegmentBlock.Run).kind)
        assertTrue(blocks[2] is SegmentBlock.Single)
    }

    private fun chunks(vararg texts: String) =
        texts.mapIndexed { index, text -> segment("s$index", AssistantSegment.Kind.MESSAGE, text) }

    private fun segment(
        id: String,
        kind: AssistantSegment.Kind,
        text: String,
    ) = AssistantSegment(id = id, kind = kind, text = text)
}
