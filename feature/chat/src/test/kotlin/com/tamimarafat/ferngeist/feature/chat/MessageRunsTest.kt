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
        assertEquals(listOf("m0", "t0", "m1"), blocks.map { it.key })
    }

    @Test
    fun reasoningNextToToolCallsJoinsTheirGroup() {
        val segments =
            listOf(
                segment("m0", AssistantSegment.Kind.MESSAGE, "one "),
                segment("m1", AssistantSegment.Kind.MESSAGE, "two"),
                segment("t0", AssistantSegment.Kind.THOUGHT, "why"),
                segment("c0", AssistantSegment.Kind.TOOL_CALL, "call"),
            )
        val blocks = segments.displayBlocks()

        assertEquals(2, blocks.size)
        assertEquals("one two", (blocks[0] as SegmentBlock.Run).text)
        val items = (blocks[1] as SegmentBlock.Group).items
        assertEquals(AssistantSegment.Kind.THOUGHT, (items[0] as SegmentBlock.Run).kind)
        assertTrue(items[1] is SegmentBlock.Single)
    }

    @Test
    fun reasoningAloneStaysAThoughtRun() {
        val blocks = listOf(segment("t0", AssistantSegment.Kind.THOUGHT, "why")).displayBlocks()

        assertEquals(AssistantSegment.Kind.THOUGHT, (blocks.single() as SegmentBlock.Run).kind)
    }

    @Test
    fun thoughtChunksInsideAGroupStillJoinAndStillResolveAsThoughtRuns() {
        val segments =
            listOf(
                segment("t0", AssistantSegment.Kind.THOUGHT, "let me "),
                segment("t1", AssistantSegment.Kind.THOUGHT, "look"),
            ) + calls("c0") + segment("t2", AssistantSegment.Kind.THOUGHT, "now edit") + calls("c1")
        val group = segments.displayBlocks().single() as SegmentBlock.Group

        assertEquals(listOf("t0", "c0", "t2", "c1"), group.items.map { it.key })
        // The reasoning sheet resolves through thoughtRuns(); a thought in a group must stay reachable.
        assertEquals(listOf("let me look", "now edit"), segments.thoughtRuns().map { it.text })
    }

    @Test
    fun adjacentToolCallsFoldIntoOneGroupKeyedOnTheFirstCall() {
        // The key must survive a new call, or the group's fold and reveal state reset mid-stream.
        val first = calls("c0").displayBlocks().single() as SegmentBlock.Group
        val third = calls("c0", "c1", "c2").displayBlocks().single() as SegmentBlock.Group

        assertEquals(first.key, third.key)
        assertEquals(listOf("c0", "c1", "c2"), third.items.map { it.key })
    }

    @Test
    fun proseBetweenToolCallsSplitsThemIntoSeparateGroups() {
        val segments =
            calls("c0") +
                segment("m0", AssistantSegment.Kind.MESSAGE, "now the next one") +
                calls("c1", "c2")
        val blocks = segments.displayBlocks()

        assertEquals(listOf("c0", "m0", "c1"), blocks.map { it.key })
        assertEquals(2, (blocks[2] as SegmentBlock.Group).items.size)
    }

    @Test
    fun plansStayOnTheirOwnBetweenToolCalls() {
        val segments = calls("c0") + segment("p0", AssistantSegment.Kind.PLAN, "") + calls("c1")
        val blocks = segments.displayBlocks()

        assertEquals(listOf("c0", "p0", "c1"), blocks.map { it.key })
        assertTrue(blocks[1] is SegmentBlock.Single)
    }

    private fun calls(vararg ids: String) = ids.map { segment(it, AssistantSegment.Kind.TOOL_CALL, "") }

    private fun chunks(vararg texts: String) =
        texts.mapIndexed { index, text -> segment("s$index", AssistantSegment.Kind.MESSAGE, text) }

    private fun segment(
        id: String,
        kind: AssistantSegment.Kind,
        text: String,
    ) = AssistantSegment(id = id, kind = kind, text = text)
}
