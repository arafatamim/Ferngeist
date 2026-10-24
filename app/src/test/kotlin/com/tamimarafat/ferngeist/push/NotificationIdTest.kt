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
    fun `fallback ids start clear of the progress range`() {
        // The incrementing fallback starts at 1000, below the progress range, so the two id
        // spaces are separate for the reachable window.
        // shortcut: the fallback counter is unbounded and walks into [5000, 6000) after
        // ~4000 non-progress notifications — a maintainer follow-up, not fixed here.
        val fallback = (1..10).map { notificationIdFor(PUSH_CATEGORY_AGENT_ERROR, null, nextId) }
        assertTrue(fallback.none { it >= 5000 && it < 6000 })
        val progress = notificationIdFor(PUSH_CATEGORY_PROGRESS, "any-session", nextId)
        assertTrue(progress >= 5000 && progress < 6000)
    }
}
