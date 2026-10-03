package com.tamimarafat.ferngeist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTitleTest {
    @Test
    fun `a blank title has no name`() {
        assertNull(sessionTitleOrNull(""))
        assertNull(sessionTitleOrNull("   "))
        assertNull(sessionTitleOrNull(null))
    }

    @Test
    fun `a name is kept as the agent wrote it`() {
        assertEquals(
            "Space Bunny Query",
            sessionTitleOrNull("Space Bunny Query", "find the space bunny"),
        )
    }

    @Test
    fun `a name is kept when there is no prompt to compare it against`() {
        assertEquals("Space Bunny Query", sessionTitleOrNull("Space Bunny Query"))
    }

    @Test
    fun `the first message is not a name`() {
        assertNull(sessionTitleOrNull("find the space bunny", "find the space bunny"))
    }

    @Test
    fun `the first message truncated for a session list is not a name`() {
        val prompt = "refactor the session title handling in the chat view model and keep it consistent"
        assertNull(sessionTitleOrNull(prompt.take(80), prompt))
    }

    @Test
    fun `a name that is only a prefix of the prompt is refused with it`() {
        assertNull(sessionTitleOrNull("find", "find the space bunny"))
    }
}
