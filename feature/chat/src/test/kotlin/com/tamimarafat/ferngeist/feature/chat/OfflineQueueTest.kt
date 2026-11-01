package com.tamimarafat.ferngeist.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineQueueTest {
    @Test
    fun `a force-send folds only the prompts after a dispatched one, which keeps its own id`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "sent", text = "sent", dispatched = true))
        queue.enqueue(PendingPrompt(clientId = "a", text = "a"))
        queue.enqueue(PendingPrompt(clientId = "b", text = "b"))

        val (folded, swallowed) = queue.foldThrough("b")!!

        assertEquals("a\n\nb", folded.text)
        assertEquals(setOf("a"), swallowed)
        assertEquals(listOf("sent", "b"), queue.snapshot().map { it.clientId })
        assertEquals(true, queue.snapshot().first().dispatched)
    }
}
