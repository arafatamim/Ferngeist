package com.tamimarafat.ferngeist.feature.chat

import com.adamglin.compose.markdown.core.model.BlockNode
import com.adamglin.compose.markdown.core.model.InlineNode
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coverage for markdown caching and hydration behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownStateStoreTest {
    @Test
    fun `onSnapshot during hydration keeps loading pending and parses assistant segments`(): Unit =
        runTest {
            var currentMessages = emptyList<ChatMessage>()
            val store =
                MarkdownStateStore()
            val message =
                assistantMessage(
                    id = "message_1",
                    text = "unused fallback",
                    segments =
                        persistentListOf(
                            AssistantSegment(
                                id = "segment_1",
                                kind = AssistantSegment.Kind.MESSAGE,
                                text = "**hello**",
                            ),
                        ),
                )
            currentMessages = listOf(message)

            val projection =
                store.onSnapshot(
                    messages = currentMessages,
                    loadState = ChatLoadState.HYDRATING,
                )

            assertTrue(projection.pendingInitialHydration)
            assertEquals(setOf("segment_1"), projection.documents.keys)
        }

    @Test
    fun `changed assistant markdown remains available after scheduler drains`() =
        runTest {
            var currentMessages =
                listOf(
                    assistantMessage(id = "message_1", text = "**before**"),
                )
            val store =
                MarkdownStateStore()

            val initial =
                store.onSnapshot(
                    messages = currentMessages,
                    loadState = ChatLoadState.READY,
                )
            assertFalse(initial.pendingInitialHydration)
            assertEquals(setOf("message_1"), initial.documents.keys)

            currentMessages =
                listOf(
                    assistantMessage(id = "message_1", text = "**after**"),
                )
            val changed =
                store.onSnapshot(
                    messages = currentMessages,
                    loadState = ChatLoadState.READY,
                )

            assertEquals(setOf("message_1"), changed.documents.keys)

            val settled =
                store.onSnapshot(
                    messages = currentMessages,
                    loadState = ChatLoadState.READY,
                )

            assertFalse(settled.pendingInitialHydration)
            assertEquals(setOf("message_1"), settled.documents.keys)
        }

    @Test
    fun `removed assistant message clears cached markdown entries`() =
        runTest {
            var currentMessages =
                listOf(
                    assistantMessage(id = "message_1", text = "**hello**"),
                )
            val store =
                MarkdownStateStore()

            val initial =
                store.onSnapshot(
                    messages = currentMessages,
                    loadState = ChatLoadState.READY,
                )
            assertEquals(setOf("message_1"), initial.documents.keys)

            currentMessages = emptyList()
            val cleared =
                store.onSnapshot(
                    messages = currentMessages,
                    loadState = ChatLoadState.READY,
                )

            assertTrue(cleared.documents.isEmpty())
            assertFalse(cleared.pendingInitialHydration)
        }

    @Test
    fun `repeated hydration snapshots parse a run once, not once per snapshot`() =
        runTest {
            val store = MarkdownStateStore()
            val message = assistantMessage(id = "message_1", text = "Hello world")

            store.onSnapshot(listOf(message), ChatLoadState.HYDRATING)
            store.onSnapshot(listOf(message), ChatLoadState.HYDRATING)
            val grown =
                store.onSnapshot(
                    listOf(message.copy(content = "Hello world, more")),
                    ChatLoadState.READY,
                )

            assertEquals("Hello world, more", grown.documents.getValue("message_1").plainText())
        }

    @Test
    fun `run that stops being a prefix of what was parsed starts over`() =
        runTest {
            val store = MarkdownStateStore()
            store.onSnapshot(listOf(assistantMessage(id = "message_1", text = "First draft")), ChatLoadState.READY)

            val rewritten =
                store.onSnapshot(listOf(assistantMessage(id = "message_1", text = "Second")), ChatLoadState.READY)

            assertEquals("Second", rewritten.documents.getValue("message_1").plainText())
        }

    @Test
    fun `unchanged run keeps the same document instance`() =
        runTest {
            val store = MarkdownStateStore()
            val messages = listOf(assistantMessage(id = "message_1", text = "Stable"))

            val first = store.onSnapshot(messages, ChatLoadState.READY)
            val second = store.onSnapshot(messages, ChatLoadState.READY)

            assertTrue(first.documents.getValue("message_1") === second.documents.getValue("message_1"))
        }

    private fun MarkdownRenderedDocument.plainText(): String =
        blocks.joinToString("|") { block ->
            (block as BlockNode.Paragraph).children.joinToString("") { (it as InlineNode.Text).literal }
        }

    /** Helper for creating assistant messages with optional segments. */
    private fun assistantMessage(
        id: String,
        text: String,
        segments: PersistentList<AssistantSegment> = persistentListOf(),
    ): ChatMessage =
        ChatMessage(
            id = id,
            role = ChatMessage.Role.ASSISTANT,
            content = text,
            segments = segments,
        )
}
