package com.tamimarafat.ferngeist.feature.chat.markdown

import com.adamglin.compose.markdown.core.api.MarkdownEngine
import com.adamglin.compose.markdown.core.dialect.MarkdownDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reveal cursor's arithmetic: rate, word cut, fade and rendered length. */
class MarkdownRevealTest {
    @Test
    fun `a burst closes most of the gap within the catch-up time constant`() {
        var shown = 0f
        repeat(21) { shown = revealStep(shown, target = 5_000, dtSeconds = 1f / 60) } // ~0.35s

        assertTrue("a 5,000-char burst must not leave the reveal seconds behind", shown >= 5_000 * 0.6f)
    }

    @Test
    fun `cursor never passes the target`() {
        assertEquals(10f, revealStep(9.9f, target = 10, dtSeconds = 1f), 0f)
        assertEquals(10f, revealStep(10f, target = 10, dtSeconds = 1f), 0f)
    }

    @Test
    fun `cut rounds up to the end of the current word`() {
        assertEquals("Hello ".length, revealCut("Hello world", local = 2f, byLine = false))
        assertEquals("Hello world".length, revealCut("Hello world", local = 7f, byLine = false))
        assertEquals(0, revealCut("Hello world", local = 0f, byLine = false))
    }

    @Test
    fun `code cuts at the end of the current line`() {
        val code = "val a = 1\nval b = 2"
        assertEquals("val a = 1\n".length, revealCut(code, local = 3f, byLine = true))
    }

    @Test
    fun `revealed characters fade from invisible to solid and the run then settles`() {
        val run = RunReveal(startHidden = true).apply { target = 4 }

        run.advance(frameMs = 1_000, dtSeconds = 1f)
        assertEquals(0f, run.alphaAt(0), 0f)
        assertTrue(run.isFading)
        assertFalse(run.isSettled)

        run.advance(frameMs = 1_000 + REVEAL_FADE_MS / 2, dtSeconds = 0f)
        assertEquals(0.5f, run.alphaAt(0), 0.01f)

        run.advance(frameMs = 1_000 + REVEAL_FADE_MS, dtSeconds = 0f)
        assertEquals(1f, run.alphaAt(0), 0f)
        assertTrue(run.isSettled)
    }

    @Test
    fun `a run composed for settled text starts fully shown`() {
        val run = RunReveal(startHidden = false).apply { target = 100 }

        assertTrue(run.isSettled)
        assertEquals(1f, run.alphaAt(99), 0f)
    }

    @Test
    fun `reveal length counts the characters the renderer draws, not markdown source`() {
        val blocks =
            MarkdownEngine(MarkdownDialect.GfmCompat)
                .append("**Bold** and `code`\n\n- one\n- two\n")
                .snapshot.document.blocks

        assertEquals("Bold and code".length + "one".length + "two".length, blocks.sumOf { it.revealLength() })
    }
}
