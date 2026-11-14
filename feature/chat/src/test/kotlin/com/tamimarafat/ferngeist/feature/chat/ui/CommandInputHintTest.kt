package com.tamimarafat.ferngeist.feature.chat.ui

import com.tamimarafat.ferngeist.core.model.ChatCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandInputHintTest {
    private val commands =
        listOf(
            ChatCommand("review", inputHint = "base branch"),
            ChatCommand("clear"),
        )

    @Test
    fun `an exact staged command shows its hint`() {
        assertEquals("base branch", activeCommandInputHint("/review ", commands))
    }

    @Test
    fun `typing an argument hides the hint`() {
        assertNull(activeCommandInputHint("/review main", commands))
    }

    @Test
    fun `a command without a hint stays hintless`() {
        assertNull(activeCommandInputHint("/clear ", commands))
    }

    @Test
    fun `a changed or removed advertisement is reflected immediately`() {
        assertEquals(
            "commit",
            activeCommandInputHint(
                "/review ",
                listOf(ChatCommand("review", inputHint = "commit")),
            ),
        )
        assertNull(
            activeCommandInputHint(
                "/review ",
                listOf(ChatCommand("review")),
            ),
        )
    }
}
