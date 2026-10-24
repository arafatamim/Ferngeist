package com.tamimarafat.ferngeist.feature.chat.syntax

import com.gallatinapps.syntaxmp.tokenizer.SyntaxTokenizer
import com.tamimarafat.ferngeist.core.common.ui.lightSyntaxColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlightingTest {
    private val colors = lightSyntaxColors
    private val tokenizer = SyntaxTokenizer()

    private fun highlighted(
        text: String,
        label: String = "kotlin",
    ): HighlightedCode = HighlightedCode(text, label, label, tokenizer.tokenize(text, label))

    @Test
    fun `an appended tail keeps the styled prefix and all characters`() {
        val old = highlighted("val x = 1")
        val newText = "val x = 1 + 2"
        val settled = styledCode(old.text, old, "kotlin", colors)
        val styled = styledCode(newText, old, "kotlin", colors)

        assertEquals(newText, styled.text)
        assertTrue(styled.spanStyles.isNotEmpty())
        // The prefix keeps exactly the settled spans, and none reaches into the tail: styling the
        // tail alone would leave the prefix plain and still pass the two assertions above.
        assertEquals(settled.spanStyles, styled.spanStyles)
        assertTrue(styled.spanStyles.all { it.end <= old.text.length })
    }

    @Test
    fun `rewritten text drops stale tokens`() {
        val old = highlighted("val x = 1")
        val styled = styledCode("fun main() {}", old, "kotlin", colors)

        assertEquals("fun main() {}", styled.text)
        assertTrue(styled.spanStyles.isEmpty())
    }

    @Test
    fun `a different label drops stale tokens`() {
        val old = highlighted("val x = 1", label = "kotlin")
        val styled = styledCode("val x = 1", old, "python", colors)

        assertTrue(styled.spanStyles.isEmpty())
    }

    @Test
    fun `null highlight is plain text`() {
        val styled = styledCode("val x = 1", null, "kotlin", colors)

        assertEquals("val x = 1", styled.text)
        assertTrue(styled.spanStyles.isEmpty())
    }
}
