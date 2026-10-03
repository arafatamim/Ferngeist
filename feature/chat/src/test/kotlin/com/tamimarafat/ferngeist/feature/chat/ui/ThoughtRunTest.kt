package com.tamimarafat.ferngeist.feature.chat.ui

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A streamed thought arrives one segment per chunk. Rendering each as its own bubble produced N
 * bubbles, and tapping one showed that chunk alone, so the sheet read as broken.
 */
class ThoughtRunTest {
    @Test
    fun tappingAnyChunkOfAThoughtShowsTheWholeReasoning() {
        val message =
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                segments =
                    persistentListOf(
                        thought("t0", "let me "),
                        thought("t1", "look "),
                        thought("t2", "closer"),
                    ),
            )

        assertEquals("let me look closer", listOf(message).thoughtForSegment("t0"))
        assertEquals("let me look closer", listOf(message).thoughtForSegment("t2"))
    }

    @Test
    fun aSingleThoughtSegmentStillResolvesToItsOwnText() {
        val message =
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                segments = persistentListOf(thought("t0", "one thought")),
            )

        assertEquals("one thought", listOf(message).thoughtForSegment("t0"))
    }

    @Test
    fun anUnknownSegmentIdResolvesToNothing() {
        val message =
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                segments = persistentListOf(thought("t0", "text")),
            )

        assertEquals(null, listOf(message).thoughtForSegment("missing"))
    }

    private fun thought(
        id: String,
        text: String,
    ) = AssistantSegment(id = id, kind = AssistantSegment.Kind.THOUGHT, text = text)
}
