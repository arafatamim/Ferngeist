package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import com.adamglin.compose.markdown.core.model.InlineNode

/**
 * Spans of prose the renderer draws as inline content instead of text: [find] gives each match's
 * range in a text or code span literal and the id it shows, and [content] the content for an id.
 */
@Immutable
internal class InlineChips(
    val find: (String) -> List<Pair<IntRange, String>>,
    val content: (String) -> InlineTextContent,
)

/** Null (no chips) by default, so previews and every renderer outside an assistant run are untouched. */
internal val localInlineChips = compositionLocalOf<InlineChips?> { null }

/**
 * Appends [literal], each of [chips]' matches as a placeholder. A chip's alternate text is the text
 * it replaces, so the rendered length — what [revealLength] counts — does not change.
 */
internal fun AnnotatedString.Builder.appendWithChips(
    literal: String,
    chips: InlineChips?,
    style: SpanStyle? = null,
) {
    var at = 0

    fun plain(end: Int) {
        if (end <= at) return
        val text = literal.substring(at, end)
        if (style == null) append(text) else withStyle(style) { append(text) }
    }
    chips?.find?.invoke(literal).orEmpty().forEach { (range, id) ->
        plain(range.first)
        // Outside [style]: a code span's background would draw behind the pill.
        appendInlineContent(id, literal.substring(range))
        at = range.last + 1
    }
    plain(literal.length)
}

/** [this] as text with [chips]' matches as placeholders, and the inline content those placeholders show. */
internal fun List<InlineNode>.toChipText(
    styles: MarkdownInlineStyles,
    chips: InlineChips?,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    if (chips == null) return toAnnotatedString(styles) to emptyMap()
    val used = LinkedHashSet<String>()
    val recording = InlineChips(find = { literal -> chips.find(literal).onEach { used += it.second } }, chips.content)
    val text = toAnnotatedString(styles, recording)
    return text to used.associateWith(chips.content)
}
