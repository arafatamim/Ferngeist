package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownStateStoreTest {

    @Test
    fun `onSnapshot during hydration keeps loading pending and parses assistant segments`(): Unit = runTest {
        var currentMessages = emptyList<ChatMessage>()
        val emittedStates = mutableListOf<Map<String, *>>()
        val store = createStore(
            scope = backgroundScope,
            currentMessages = { currentMessages },
            onMarkdownStatesChanged = { emittedStates += it },
        )
        val message = assistantMessage(
            id = "message_1",
            text = "unused fallback",
            segments = listOf(
                AssistantSegment(
                    id = "segment_1",
                    kind = AssistantSegment.Kind.MESSAGE,
                    text = "**hello**"
                )
            )
        )
        currentMessages = listOf(message)

        val projection = store.onSnapshot(
            messages = currentMessages,
            loadState = SessionLoadState.HYDRATING,
        )

        assertTrue(projection.pendingInitialHydration)
        assertEquals(setOf("segment_1"), projection.markdownStates.keys)
        assertTrue(emittedStates.isEmpty())
    }

    @Test
    fun `changed assistant markdown remains available after scheduler drains`() = runTest {
        var currentMessages = listOf(
            assistantMessage(id = "message_1", text = "**before**")
        )
        val store = createStore(
            scope = backgroundScope,
            currentMessages = { currentMessages },
        )

        val initial = store.onSnapshot(
            messages = currentMessages,
            loadState = SessionLoadState.READY,
        )
        assertFalse(initial.pendingInitialHydration)
        assertEquals(setOf("message_1"), initial.markdownStates.keys)

        currentMessages = listOf(
            assistantMessage(id = "message_1", text = "**after**")
        )
        val changed = store.onSnapshot(
            messages = currentMessages,
            loadState = SessionLoadState.READY,
        )

        assertEquals(setOf("message_1"), changed.markdownStates.keys)

        advanceUntilIdle()

        val settled = store.onSnapshot(
            messages = currentMessages,
            loadState = SessionLoadState.READY,
        )

        assertFalse(settled.pendingInitialHydration)
        assertEquals(setOf("message_1"), settled.markdownStates.keys)
    }

    @Test
    fun `removed assistant message clears cached markdown entries`() = runTest {
        var currentMessages = listOf(
            assistantMessage(id = "message_1", text = "**hello**")
        )
        val store = createStore(
            scope = backgroundScope,
            currentMessages = { currentMessages },
        )

        val initial = store.onSnapshot(
            messages = currentMessages,
            loadState = SessionLoadState.READY,
        )
        assertEquals(setOf("message_1"), initial.markdownStates.keys)

        currentMessages = emptyList()
        val cleared = store.onSnapshot(
            messages = currentMessages,
            loadState = SessionLoadState.READY,
        )

        assertTrue(cleared.markdownStates.isEmpty())
        assertFalse(cleared.pendingInitialHydration)
    }

    private fun createStore(
        scope: CoroutineScope,
        currentMessages: () -> List<ChatMessage>,
        onMarkdownStatesChanged: (Map<String, *>) -> Unit = {},
    ): MarkdownStateStore {
        return MarkdownStateStore(
            scope = scope,
            currentMessages = currentMessages,
            onMarkdownStatesChanged = { onMarkdownStatesChanged(it) },
            trace = {},
        )
    }

    private fun assistantMessage(
        id: String,
        text: String,
        segments: List<AssistantSegment> = emptyList(),
    ): ChatMessage {
        return ChatMessage(
            id = id,
            role = ChatMessage.Role.ASSISTANT,
            content = text,
            segments = segments,
        )
    }
}
