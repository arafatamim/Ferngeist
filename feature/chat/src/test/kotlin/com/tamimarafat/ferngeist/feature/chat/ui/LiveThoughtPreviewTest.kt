package com.tamimarafat.ferngeist.feature.chat.ui

import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.collapseThought
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.liveThoughtPreview
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveThoughtPreviewTest {
    @Test
    fun collapsesWhitespaceToSingleLine() {
        assertEquals("thinking about files", collapseThought("thinking\nabout\t files"))
    }

    @Test
    fun blankCollapsesToBlank() {
        assertEquals("", collapseThought("   \n "))
    }

    @Test
    fun keepsTailOfReleasedPrefixSoNewestTokensStayVisible() {
        assertEquals("cde", liveThoughtPreview("abcde", shown = 5, maxChars = 3))
    }

    @Test
    fun revealWindowGrowsWithTheCursor() {
        assertEquals("", liveThoughtPreview("abcde", shown = 0))
        assertEquals("ab", liveThoughtPreview("abcde", shown = 2))
        assertEquals("abcde", liveThoughtPreview("abcde", shown = 5))
    }

    @Test
    fun revealWindowNeverReadsPastTheRun() {
        assertEquals("abcde", liveThoughtPreview("abcde", shown = 99))
    }
}
