package com.tamimarafat.ferngeist.feature.chat.syntax

import androidx.compose.ui.graphics.Color
import com.gallatinapps.syntaxmp.language.LanguageId
import com.gallatinapps.syntaxmp.role.SyntaxRole
import com.gallatinapps.syntaxmp.spans.SyntaxTokenSpan
import com.gallatinapps.syntaxmp.tokenizer.SyntaxTokenizer
import com.tamimarafat.ferngeist.core.common.ui.lightSyntaxColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxSpansTest {
    private val colors = lightSyntaxColors
    private val tokenizer = SyntaxTokenizer()

    @Test
    fun `styling keeps every character and every span in bounds`() {
        val code = "val answer = \"forty-two\" // done\n"
        val tokens = tokenizer.tokenize(code, "kotlin")
        val styled = buildSyntaxAnnotatedString(code, tokens, colors)

        assertEquals(code, styled.text)
        assertTrue(styled.spanStyles.isNotEmpty())
        assertTrue(styled.spanStyles.all { it.start >= 0 && it.end <= code.length })
    }

    @Test
    fun `a role with no direct color falls back to its parent`() {
        val declaration = SyntaxTokenSpan(0, 3, SyntaxRole.Keyword.Declaration, LanguageId.Kotlin)
        val styled = buildSyntaxAnnotatedString("val x", listOf(declaration), colors)
        val style = styled.spanStyles.single()
        assertEquals(colors.keyword, style.item.color)
    }

    @Test
    fun `out of bounds spans are clipped instead of throwing`() {
        val span = SyntaxTokenSpan(0, 999, SyntaxRole.Keyword, LanguageId.Kotlin)
        val styled = buildSyntaxAnnotatedString("val", listOf(span), colors)
        assertEquals(0, styled.spanStyles.single().start)
        assertEquals(3, styled.spanStyles.single().end)
    }

    @Test
    fun `the same tokens restyle for the other theme`() {
        val code = "val answer = 42"
        val tokens = tokenizer.tokenize(code, "kotlin")
        val lightPalette = colors.copy(keyword = Color(0xFF102030))
        val darkPalette = colors.copy(keyword = Color(0xFF203040))
        val light = buildSyntaxAnnotatedString(code, tokens, lightPalette)
        val dark = buildSyntaxAnnotatedString(code, tokens, darkPalette)

        assertEquals(light.text, dark.text)
        assertEquals(
            light.spanStyles.map { it.start to it.end },
            dark.spanStyles.map { it.start to it.end },
        )
        val lightKeyword = light.spanStyles.first { it.item.color == lightPalette.keyword }
        val darkKeyword = dark.spanStyles.first { it.item.color == darkPalette.keyword }
        assertEquals(lightKeyword.start to lightKeyword.end, darkKeyword.start to darkKeyword.end)
    }

    @Test
    fun `per-line split preserves text and count`() {
        val code = "val a = 1\n// note\n"
        val lines = buildSyntaxLines(code, tokenizer.tokenize(code, "kotlin"), colors)
        assertEquals(listOf("val a = 1", "// note"), lines.map { it.text })
        assertTrue(lines[1].spanStyles.isNotEmpty())
    }

    @Test
    fun `crlf files keep their carriage returns out of the rendered line`() {
        val code = "val a = 1\r\nval b = 2\r\n"
        val lines = buildSyntaxLines(code, tokenizer.tokenize(code, "kotlin"), colors)
        assertEquals(listOf("val a = 1", "val b = 2"), lines.map { it.text })
    }

    @Test
    fun `a lone carriage return splits lines like Kotlin lines`() {
        val code = "val a = 1\rval b = 2"
        val lines = buildSyntaxLines(code, tokenizer.tokenize(code, "kotlin"), colors)
        assertEquals(listOf("val a = 1", "val b = 2"), lines.map { it.text })
    }
}
