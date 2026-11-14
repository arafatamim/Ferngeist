package com.tamimarafat.ferngeist.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMentionTest {
    @Test
    fun fuzzySearchMatchesNonconsecutiveCharactersIgnoringCase() {
        assertEquals(
            listOf("src/ChatComposerHost.kt"),
            matchingWorkspaceFiles(listOf("src/ChatComposerHost.kt", "src/Other.kt"), "cch"),
        )
    }

    @Test
    fun queryOnlyStartsAtWordBoundaryAndUsesCursor() {
        assertEquals("cm", workspaceMentionQuery("hello @cm world", 9)?.second)
        assertEquals(null, workspaceMentionQuery("a@cm", 4))
    }

    @Test
    fun keyboardSelectionStaysWithinResults() {
        assertEquals(1, moveMentionSelection(0, 1, 3))
        assertEquals(2, moveMentionSelection(2, 1, 3))
        assertEquals(0, moveMentionSelection(0, -1, 3))
    }
}
