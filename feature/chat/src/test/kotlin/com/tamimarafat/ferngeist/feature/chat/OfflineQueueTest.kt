package com.tamimarafat.ferngeist.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQueueTest {
    @Test
    fun `dequeue returns null when queue is empty`() {
        assertNull(OfflineQueue().dequeue())
    }

    @Test
    fun `dequeue returns prompts in FIFO order`() {
        val queue = OfflineQueue()
        val a = PendingPrompt(clientId = "a", text = "first")
        val b = PendingPrompt(clientId = "b", text = "second")
        val c = PendingPrompt(clientId = "c", text = "third")
        listOf(a, b, c).forEach(queue::enqueue)

        assertEquals(3, queue.size)
        assertEquals(a, queue.dequeue())
        assertEquals(b, queue.dequeue())
        assertEquals(c, queue.dequeue())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `isEmpty returns false after enqueue`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "a", text = "x"))
        assertFalse(queue.isEmpty)
        assertEquals(1, queue.size)
    }

    @Test
    fun `removeByClientId removes matching prompt and keeps the rest in order`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "keep", text = "a"))
        queue.enqueue(PendingPrompt(clientId = "drop", text = "b"))
        queue.enqueue(PendingPrompt(clientId = "keep2", text = "c"))

        assertTrue(queue.removeByClientId("drop"))
        assertEquals(listOf("keep", "keep2"), queue.snapshot().map { it.clientId })
    }

    @Test
    fun `removeByClientId returns false when no match`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "a", text = "x"))
        assertFalse(queue.removeByClientId("nonexistent"))
        assertEquals(1, queue.size)
    }

    @Test
    fun `snapshot returns all prompts in FIFO order without modifying queue`() {
        val queue = OfflineQueue()
        val a = PendingPrompt(clientId = "a", text = "first")
        val b = PendingPrompt(clientId = "b", text = "second")
        queue.enqueue(a)
        queue.enqueue(b)

        assertEquals(listOf(a, b), queue.snapshot())
        assertEquals(2, queue.size)
    }

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
