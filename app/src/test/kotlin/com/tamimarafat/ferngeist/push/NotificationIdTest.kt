package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.push.notificationIdFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdTest {
    private var next = 1000
    private val nextId: () -> Int = { next++ }

    @Test
    fun `progress with same session uses stable id to replace not stack`() {
        val first = notificationIdFor(PUSH_CATEGORY_PROGRESS, "sess-1", nextId)
        val second = notificationIdFor(PUSH_CATEGORY_PROGRESS, "sess-1", nextId)
        assertEquals(first, second)
        // The incrementing fallback wasn't consumed.
        assertEquals(1000, next)
    }

    @Test
    fun `progress with different sessions uses different ids`() {
        val a = notificationIdFor(PUSH_CATEGORY_PROGRESS, "sess-1", nextId)
        val b = notificationIdFor(PUSH_CATEGORY_PROGRESS, "sess-2", nextId)
        assertNotEquals(a, b)
    }

    @Test
    fun `progress without session id falls back to incrementing`() {
        val first = notificationIdFor(PUSH_CATEGORY_PROGRESS, null, nextId)
        val second = notificationIdFor(PUSH_CATEGORY_PROGRESS, null, nextId)
        assertNotEquals(first, second)
        assertEquals(1001, second)
    }

    @Test
    fun `non-progress categories keep stacking`() {
        val first = notificationIdFor(PUSH_CATEGORY_AGENT_ERROR, "sess-1", nextId)
        val second = notificationIdFor(PUSH_CATEGORY_AGENT_ERROR, "sess-1", nextId)
        assertNotEquals(first, second)
        assertEquals(1001, second)
    }

    @Test
    fun `progress ids stay within the dedicated range`() {
        val id = notificationIdFor(PUSH_CATEGORY_PROGRESS, "any-session", nextId)
        assertTrue(id >= 5000 && id < 6000)
    }
}
