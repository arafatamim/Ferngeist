package com.tamimarafat.ferngeist.feature.chat.syntax

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyntaxHighlighterTest {
    private val highlighter = SyntaxHighlighter(dispatcher = UnconfinedTestDispatcher())

    @Test
    fun `alias resolves to the canonical language and yields spans`() =
        runTest {
            val result = highlighter.highlight("val x = 1", "kt")!!

            assertEquals("kt", result.label)
            assertEquals("kotlin", result.language)
            assertEquals("val x = 1", result.text)
            assertTrue(result.tokens.isNotEmpty())
        }

    @Test
    fun `unknown or blank labels stay plain`() =
        runTest {
            assertNull(highlighter.highlight("val x = 1", "not-a-language"))
            assertNull(highlighter.highlight("val x = 1", null))
            assertNull(highlighter.highlight("val x = 1", "  "))
        }

    @Test
    fun `oversized input is not tokenized`() =
        runTest {
            assertNull(highlighter.highlight("x".repeat(SyntaxHighlighter.MAX_CHARS + 1), "kotlin"))
        }

    @Test
    fun `the second call for the same text reuses cached tokens`() =
        runTest {
            val first = highlighter.highlight("val x = 1", "kotlin")!!
            val second = highlighter.highlight("val x = 1", "kotlin")!!

            assertSame(first.tokens, second.tokens)
        }
}
