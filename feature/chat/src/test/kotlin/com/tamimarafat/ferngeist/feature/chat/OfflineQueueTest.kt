package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQueueTest {
    // region: Enqueue / Dequeue

    @Test
    fun `dequeue returns null when queue is empty`() {
        val queue = OfflineQueue()
        assertNull(queue.dequeue())
    }

    @Test
    fun `dequeue returns prompts in FIFO order`() {
        val queue = OfflineQueue()
        val a = PendingPrompt(clientId = "a", text = "first")
        val b = PendingPrompt(clientId = "b", text = "second")
        val c = PendingPrompt(clientId = "c", text = "third")

        queue.enqueue(a)
        queue.enqueue(b)
        queue.enqueue(c)

        assertEquals(3, queue.size)
        assertEquals(a, queue.dequeue())
        assertEquals(b, queue.dequeue())
        assertEquals(c, queue.dequeue())
        assertEquals(0, queue.size)
    }

    @Test
    fun `peek returns first prompt without removing it`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "x", text = "hello"))
        assertEquals("x", queue.peek()?.clientId)
        assertEquals(1, queue.size)
    }

    @Test
    fun `peek returns null when queue is empty`() {
        val queue = OfflineQueue()
        assertNull(queue.peek())
    }

    // endregion

    // region: isEmpty / size

    @Test
    fun `isEmpty returns true when no prompts are queued`() {
        val queue = OfflineQueue()
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.size)
    }

    @Test
    fun `isEmpty returns false after enqueue`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "a", text = "x"))
        assertFalse(queue.isEmpty)
        assertEquals(1, queue.size)
    }

    @Test
    fun `isEmpty returns true after enqueue then dequeue all`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "a", text = "x"))
        queue.dequeue()
        assertTrue(queue.isEmpty)
    }

    // endregion

    // region: removeByClientId

    @Test
    fun `removeByClientId removes matching prompt`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "keep", text = "a"))
        queue.enqueue(PendingPrompt(clientId = "drop", text = "b"))
        queue.enqueue(PendingPrompt(clientId = "keep2", text = "c"))

        val removed = queue.removeByClientId("drop")
        assertTrue(removed)
        assertEquals(2, queue.size)
        assertEquals("keep", queue.dequeue()?.clientId)
        assertEquals("keep2", queue.dequeue()?.clientId)
    }

    @Test
    fun `removeByClientId returns false when no match`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "a", text = "x"))
        val removed = queue.removeByClientId("nonexistent")
        assertFalse(removed)
        assertEquals(1, queue.size)
    }

    // endregion

    // region: clear

    @Test
    fun `clear removes all pending prompts`() {
        val queue = OfflineQueue()
        queue.enqueue(PendingPrompt(clientId = "a", text = "x"))
        queue.enqueue(PendingPrompt(clientId = "b", text = "y"))
        queue.clear()
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.size)
    }

    // endregion

    // region: snapshot

    @Test
    fun `snapshot returns all prompts in FIFO order without modifying queue`() {
        val queue = OfflineQueue()
        val a = PendingPrompt(clientId = "a", text = "first")
        val b = PendingPrompt(clientId = "b", text = "second")
        queue.enqueue(a)
        queue.enqueue(b)

        val snap = queue.snapshot()
        assertEquals(listOf(a, b), snap)
        assertEquals(2, queue.size) // queue unchanged
    }

    @Test
    fun `snapshot returns empty list for empty queue`() {
        val queue = OfflineQueue()
        assertEquals(emptyList<PendingPrompt>(), queue.snapshot())
    }

    // endregion

    // region: Status transition helpers (pure logic tests)

    @Test
    fun `pending prompt carries clientId linking it to the UI message`() {
        val prompt =
            PendingPrompt(
                clientId = "client-42",
                text = "Hello",
                images = listOf(ChatImageData(base64 = "abc", mimeType = "image/png")),
            )
        assertEquals("client-42", prompt.clientId)
        assertEquals("Hello", prompt.text)
        assertEquals(1, prompt.images.size)
        assertEquals("abc", prompt.images[0].base64)
    }

    @Test
    fun `ChatMessage default status is SENT for loaded history`() {
        val msg =
            com.tamimarafat.ferngeist.core.model.ChatMessage(
                role = com.tamimarafat.ferngeist.core.model.ChatMessage.Role.ASSISTANT,
                content = "server response",
            )
        assertEquals(MessageDeliveryStatus.SENT, msg.status)
        assertNull(msg.clientId)
    }

    @Test
    fun `ChatMessage can be constructed with QUEUED status and clientId`() {
        val msg =
            com.tamimarafat.ferngeist.core.model.ChatMessage(
                id = "msg-1",
                role = com.tamimarafat.ferngeist.core.model.ChatMessage.Role.USER,
                content = "offline message",
                status = MessageDeliveryStatus.QUEUED,
                clientId = "client-1",
            )
        assertEquals(MessageDeliveryStatus.QUEUED, msg.status)
        assertEquals("client-1", msg.clientId)
    }

    // endregion
}
