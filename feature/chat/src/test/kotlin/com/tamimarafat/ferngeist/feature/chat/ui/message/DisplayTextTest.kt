package com.tamimarafat.ferngeist.feature.chat.ui.message

import com.agentclientprotocol.model.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTextTest {
    @Test
    fun cappedForDisplay_shortText_isUnchanged() {
        assertEquals("hello", "hello".cappedForDisplay())
    }

    @Test
    fun cappedForDisplay_hugeUnbreakableLine_isCutWithEllipsis() {
        val capped = "A".repeat(700_000).cappedForDisplay()

        assertTrue(capped.length < 20_000)
        assertTrue(capped.endsWith("…"))
    }

    @Test
    fun mixedContent_sharesOneTextBudgetWithoutDroppingAttachments() {
        val audio = ContentBlock.Audio(data = "YXVkaW8=", mimeType = "audio/wav")
        val blocks = listOf(ContentBlock.Text("A".repeat(8000)), audio, ContentBlock.Text("B".repeat(8000)))

        assertEquals(
            listOf(ContentBlock.Text("A".repeat(8000)), audio, ContentBlock.Text("B".repeat(2000) + "…")),
            blocks.cappedUserContent(),
        )
    }
}
