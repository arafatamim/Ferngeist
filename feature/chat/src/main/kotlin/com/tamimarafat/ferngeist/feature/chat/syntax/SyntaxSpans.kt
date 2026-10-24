package com.tamimarafat.ferngeist.feature.chat.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import com.gallatinapps.syntaxmp.role.SyntaxRole
import com.gallatinapps.syntaxmp.spans.SyntaxTokenSpan
import com.tamimarafat.ferngeist.core.common.ui.SyntaxColors

/**
 * Turns token spans into styled text. Styling only: the returned text is always [code] exactly,
 * which is what keeps the streaming reveal's character counts valid.
 */
internal fun buildSyntaxAnnotatedString(
    code: String,
    tokens: List<SyntaxTokenSpan>,
    colors: SyntaxColors,
): AnnotatedString {
    if (tokens.isEmpty()) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)
        tokens.forEach { token ->
            val start = token.start.coerceIn(0, code.length)
            val end = token.endExclusive.coerceIn(start, code.length)
            if (end > start) {
                syntaxStyle(token.role, colors)?.let { addStyle(it, start, end) }
            }
        }
    }
}

/**
 * One [AnnotatedString] per line, for surfaces that render lines individually (the file sheet and
 * the diff rows). The line count matches `String.diffLines()`: a trailing newline ends the last
 * line rather than adding an empty one.
 */
internal fun buildSyntaxLines(
    code: String,
    tokens: List<SyntaxTokenSpan>,
    colors: SyntaxColors,
): List<AnnotatedString> {
    val ranges = code.lineRanges()
    var firstLiveToken = 0
    return ranges.map { range ->
        val lineStart = range.first
        val lineEnd = range.last + 1
        while (firstLiveToken < tokens.size && tokens[firstLiveToken].endExclusive <= lineStart) {
            firstLiveToken++
        }
        buildAnnotatedString {
            append(code.substring(lineStart, lineEnd))
            var index = firstLiveToken
            while (index < tokens.size && tokens[index].start < lineEnd) {
                val token = tokens[index]
                val start = (token.start.coerceAtLeast(lineStart) - lineStart)
                val end = (token.endExclusive.coerceAtMost(lineEnd) - lineStart)
                if (end > start) {
                    syntaxStyle(token.role, colors)?.let { addStyle(it, start, end) }
                }
                index++
            }
        }
    }
}

/** Half-open offsets of each line, matching `String.lines()`: `\n`, `\r\n`, or a lone `\r` ends a line. */
internal fun String.lineRanges(): List<IntRange> {
    if (isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = 0
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '\n' || char == '\r') {
            ranges += start until index
            if (char == '\r' && index + 1 < length && this[index + 1] == '\n') index++
            start = index + 1
        }
        index++
    }
    if (start < length) ranges += start until length
    return ranges
}

private fun syntaxStyle(
    role: SyntaxRole,
    colors: SyntaxColors,
): SpanStyle? {
    val color =
        role
            .rolesFromRoot()
            .asReversed()
            .firstNotNullOfOrNull { colors.colorFor(it.value) }
            ?: return null
    return SpanStyle(
        color = color,
        fontStyle = if (role.value == "comment" || role.value.startsWith("comment.")) FontStyle.Italic else null,
    )
}

/** The colour each syntax role takes from the theme; a role without one renders plain. */
private val syntaxRoleColors: Map<String, (SyntaxColors) -> Color?> =
    mapOf(
        "keyword" to { colors -> colors.keyword },
        "string" to { colors -> colors.string },
        "number" to { colors -> colors.number },
        "comment" to { colors -> colors.comment },
        "function" to { colors -> colors.function },
        "type" to { colors -> colors.type },
        "property" to { colors -> colors.property },
        "variable" to { colors -> colors.variable },
        "operator" to { colors -> colors.operator },
        "punctuation" to { colors -> colors.punctuation },
        "annotation" to { colors -> colors.annotation },
        "tag" to { colors -> colors.tag },
        "attribute" to { colors -> colors.attribute },
        "constant" to { colors -> colors.constant },
        "escape" to { colors -> colors.escape },
        "markup" to { colors -> colors.markup },
    )

private fun SyntaxColors.colorFor(role: String): Color? = syntaxRoleColors[role]?.invoke(this)
