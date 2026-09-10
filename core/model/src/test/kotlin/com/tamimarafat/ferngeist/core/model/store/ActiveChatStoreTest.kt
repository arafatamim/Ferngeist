package com.tamimarafat.ferngeist.core.model.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveChatStoreTest {
    private fun chat(sessionId: String) =
        ActiveChat(serverId = "srv", sessionId = sessionId, cwd = "/", title = sessionId)

    @Test
    fun `setActiveChat moves chat to head and dedups`() {
        val store = ActiveChatStore()
        store.setActiveChat(chat("a"))
        store.setActiveChat(chat("b"))
        store.setActiveChat(chat("a"))
        assertEquals(listOf("a", "b"), store.openChats.value.map { it.sessionId })
        assertEquals("a", store.activeChat.value?.sessionId)
    }

    @Test
    fun `remove drops only that chat`() {
        val store = ActiveChatStore()
        store.setActiveChat(chat("a"))
        store.setActiveChat(chat("b"))
        store.clearIfCurrent("b")
        assertEquals(listOf("a"), store.openChats.value.map { it.sessionId })
    }

    @Test
    fun `closing the head promotes the next most recent chat`() {
        val store = ActiveChatStore()
        store.setActiveChat(chat("a"))
        store.setActiveChat(chat("b"))
        store.clearIfCurrent("b")
        assertEquals("a", store.activeChat.value?.sessionId)
    }

    @Test
    fun `closing the last chat clears active`() {
        val store = ActiveChatStore()
        store.setActiveChat(chat("a"))
        store.clearIfCurrent("a")
        assertNull(store.activeChat.value)
        assertEquals(0, store.openChats.value.size)
    }

    @Test
    fun `clearing an unknown session is a no-op`() {
        val store = ActiveChatStore()
        store.setActiveChat(chat("a"))
        store.clearIfCurrent("zzz")
        assertEquals(listOf("a"), store.openChats.value.map { it.sessionId })
        assertEquals("a", store.activeChat.value?.sessionId)
    }
}
