package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import androidx.compose.foundation.lazy.LazyListState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The space reserved under a just-sent prompt so it can sit at the top of the viewport. */
class RunwayTest {
    @Test
    fun `a chat's first send puts the runway under the new prompt in the frame it appears`() {
        val old = ChatMessage(id = "old", role = ChatMessage.Role.USER)
        val reply = ChatMessage(id = "reply", role = ChatMessage.Role.ASSISTANT)
        val sent = ChatMessage(id = "sent", role = ChatMessage.Role.USER)
        val scroll = ChatScrollHandle(LazyListState())
        scroll.onMessages(listOf(old, reply))
        assertNull(scroll.runwayPrompt(listOf(old, reply)))

        scroll.onSendMessage()
        // Before the prompt is echoed back, the old one must not take the reserve.
        assertNull(scroll.runwayPrompt(listOf(old, reply)))
        assertEquals(sent, scroll.runwayPrompt(listOf(old, reply, sent)))
    }
}
