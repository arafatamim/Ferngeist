package com.tamimarafat.ferngeist.feature.chat.ui

import com.agentclientprotocol.model.ContentBlock
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

        assertEquals(
            listOf(ContentBlock.Text("let me "), ContentBlock.Text("look "), ContentBlock.Text("closer")),
            listOf(message).thoughtForSegment("t0"),
        )
        assertEquals(
            listOf(ContentBlock.Text("let me "), ContentBlock.Text("look "), ContentBlock.Text("closer")),
            listOf(message).thoughtForSegment("t2"),
        )
    }

    @Test
    fun aSingleThoughtSegmentResolvesToItsOwnText() {
        val message =
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                segments = persistentListOf(thought("t0", "one thought")),
            )

        assertEquals(listOf(ContentBlock.Text("one thought")), listOf(message).thoughtForSegment("t0"))
    }

    @Test
    fun mixedThoughtKeepsAttachmentsBetweenTextChunks() {
        val image = ContentBlock.Image(data = "aW1n", mimeType = "image/png")
        val message =
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                segments =
                    persistentListOf(
                        thought("t0", "before"),
                        AssistantSegment(id = "t1", kind = AssistantSegment.Kind.THOUGHT, contentBlock = image),
                        thought("t2", "after"),
                    ),
            )

        assertEquals(
            listOf(ContentBlock.Text("before"), image, ContentBlock.Text("after")),
            listOf(message).thoughtForSegment("t1"),
        )
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
    ) = AssistantSegment(id = id, kind = AssistantSegment.Kind.THOUGHT, contentBlock = ContentBlock.Text(text))
}
