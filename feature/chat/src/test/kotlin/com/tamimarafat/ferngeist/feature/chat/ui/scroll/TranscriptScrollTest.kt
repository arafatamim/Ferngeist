package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import androidx.compose.foundation.lazy.LazyListState
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ui.transcript.TranscriptRows
import com.tamimarafat.ferngeist.feature.chat.ui.transcript.toTurns
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptScrollTest {
    private val prompt = ChatMessage(id = "prompt", role = ChatMessage.Role.USER)

    private fun rows(vararg messages: ChatMessage) =
        TranscriptRows(resumedSession = true, hasOlder = true, turns = messages.toList().toTurns())

    @Test
    fun `opening a live chat starts at the end without following later reply growth`() {
        val list = LazyListState()
        val scroll = ChatScrollHandle(list)
        val reply = ChatMessage(id = "reply", role = ChatMessage.Role.ASSISTANT, isStreaming = true)
        scroll.live = true

        scroll.rows = rows(prompt, reply)

        assertEquals(3, list.firstVisibleItemIndex)
        list.requestScrollToItem(2, 40)
        val grown = reply.copy(segments = persistentListOf(AssistantSegment(contentBlock = ContentBlock.Text("more"))))
        scroll.rows = rows(prompt, grown)
        assertEquals(2, list.firstVisibleItemIndex)
        assertEquals(40, list.firstVisibleItemScrollOffset)
    }

    @Test
    fun `a settled message landing in the last turn is followed`() {
        val list = LazyListState()
        val scroll = ChatScrollHandle(list)
        val reply = ChatMessage(id = "reply", role = ChatMessage.Role.ASSISTANT)
        scroll.rows = rows(prompt, reply)
        list.requestScrollToItem(2, 40)

        scroll.rows = rows(prompt, reply, ChatMessage(id = "error", role = ChatMessage.Role.ASSISTANT, isError = true))

        assertEquals(3, list.firstVisibleItemIndex)
    }
}
