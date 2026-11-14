package com.tamimarafat.ferngeist.feature.chat.ui

import com.tamimarafat.ferngeist.feature.chat.ui.composer.matchingSuggestions
import com.tamimarafat.ferngeist.feature.chat.ui.composer.moveSuggestionSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMentionTest {
    @Test
    fun fuzzySearchMatchesNonconsecutiveCharactersIgnoringCase() {
        assertEquals(
            listOf("src/ChatComposerHost.kt"),
            matchingSuggestions(listOf("src/ChatComposerHost.kt", "src/Other.kt"), "cch"),
        )
    }

    @Test
    fun queryOnlyStartsAtWordBoundaryAndUsesCursor() {
        assertEquals("cm", workspaceMentionQuery("hello @cm world", 9)?.second)
        assertEquals(null, workspaceMentionQuery("a@cm", 4))
    }

    @Test
    fun keyboardSelectionStaysWithinResults() {
        assertEquals(1, moveSuggestionSelection(0, 1, 3))
        assertEquals(2, moveSuggestionSelection(2, 1, 3))
        assertEquals(0, moveSuggestionSelection(0, -1, 3))
    }

    @Test
    fun slashCommandQueryOnlyMatchesTheCommandAtTheStart() {
        assertEquals("rev", slashCommandQuery("/rev", 4))
        assertEquals("", slashCommandQuery("/", 1))
        assertEquals(null, slashCommandQuery("open /src", 9))
        assertEquals(null, slashCommandQuery("/review main", 12))
    }
}
