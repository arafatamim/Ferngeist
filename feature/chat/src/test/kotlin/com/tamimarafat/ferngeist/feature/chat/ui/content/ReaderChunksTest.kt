package com.tamimarafat.ferngeist.feature.chat.ui.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChunksTest {
    @Test
    fun readerChunks_preferLineBreaksAndCoverTheWholeText() {
        val line = "x".repeat(3000) + "\n"
        val text = line + line + "tail"

        val chunks = text.readerChunks()

        assertEquals(listOf(0 until 3001, 3001 until 6006), chunks)
    }

    @Test
    fun readerChunks_neverSplitASurrogatePairInAnUnbrokenLine() {
        val text = "a".repeat(4095) + "😀" + "b".repeat(10)

        val chunks = text.readerChunks()

        assertEquals(text, chunks.joinToString("") { text.substring(it) })
        assertTrue(chunks.none { text[it.last].isHighSurrogate() })
    }
}
