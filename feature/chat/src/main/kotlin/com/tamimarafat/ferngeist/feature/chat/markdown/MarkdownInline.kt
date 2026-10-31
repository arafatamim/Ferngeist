package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.adamglin.compose.markdown.core.model.InlineNode

internal fun List<InlineNode>.toAnnotatedString(
    styles: MarkdownInlineStyles =
        MarkdownInlineStyles(
            emphasis = SpanStyle(fontStyle = FontStyle.Italic),
            strong = SpanStyle(fontWeight = FontWeight.Bold),
            strike = SpanStyle(textDecoration = TextDecoration.LineThrough),
            code = SpanStyle(fontFamily = FontFamily.Monospace),
            link =
                TextLinkStyles(
                    style = SpanStyle(textDecoration = TextDecoration.Underline),
                ),
        ),
    chips: InlineChips? = null,
): AnnotatedString =
    buildAnnotatedString {
        appendInlineNodes(nodes = this@toAnnotatedString, styles = styles, chips = chips)
    }

/** [localInlineChips] once [reveal] has settled: a chip landing mid-reveal would jump under the cursor. */
@Composable
internal fun settledChips(reveal: BlockReveal?): InlineChips? {
    val chips = localInlineChips.current ?: return null
    val settled by remember(reveal) { derivedStateOf { reveal == null || reveal.run.isSettled } }
    return chips.takeIf { settled }
}

@Composable
internal fun MarkdownInlineText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = true,
) {
    BasicText(
        text = text,
        style = style,
        modifier = if (fillMaxWidth) modifier.fillMaxWidth() else modifier,
    )
}

/** Body text that reveals with [reveal], [localInlineChips]' matches drawn once it settles. */
@Composable
internal fun RevealInlines(
    inlines: List<InlineNode>,
    styles: MarkdownBlockStyles,
    modifier: Modifier,
    reveal: BlockReveal?,
) {
    val (text, inlineContent) = inlines.toChipText(styles.inline, settledChips(reveal))
    RevealText(
        text = text,
        style = MarkdownTheme.typography.bodyLarge,
        reveal = reveal,
        inlineContent = inlineContent,
        modifier = modifier.fillMaxWidth(),
    )
}

private fun AnnotatedString.Builder.appendInlineNodes(
    nodes: List<InlineNode>,
    styles: MarkdownInlineStyles,
    chips: InlineChips? = null,
) {
    nodes.forEach { node ->
        when (node) {
            is InlineNode.CodeSpan -> appendWithChips(node.literal, chips, styles.code)

            is InlineNode.Emphasis ->
                withStyle(styles.emphasis) {
                    appendInlineNodes(node.children, styles, chips)
                }

            is InlineNode.HardBreak -> append("\n")

            // No chips inside a link: the tap already goes to its destination.
            is InlineNode.Link ->
                withLink(
                    LinkAnnotation.Url(
                        url = node.destination,
                        styles = styles.link,
                    ),
                ) {
                    appendInlineNodes(node.children, styles)
                }

            is InlineNode.SoftBreak -> append("\n")

            is InlineNode.Image -> appendInlineNodes(node.alt, styles)

            is InlineNode.Strikethrough ->
                withStyle(styles.strike) {
                    appendInlineNodes(node.children, styles, chips)
                }

            is InlineNode.Strong ->
                withStyle(styles.strong) {
                    appendInlineNodes(node.children, styles, chips)
                }

            is InlineNode.Text -> appendWithChips(node.literal, chips)

            is InlineNode.UnsupportedInline -> append(node.literal)

            // ponytail: math renders as its raw source. No LaTeX renderer is wired up; swap in a
            // MathRenderer only if an agent actually emits $$ math in chat.
            is InlineNode.MathSpan -> append(node.latex)
        }
    }
}

/** A paragraph's inlines split around its top-level images, so each image lays out on its own. */
internal sealed interface InlineSegment {
    data class Text(
        val nodes: List<InlineNode>,
    ) : InlineSegment

    data class Image(
        val node: InlineNode.Image,
    ) : InlineSegment
}

/**
 * [this] split at its top-level images, in source order. Line breaks next to an image and runs of
 * nothing but whitespace are dropped: the image's own line already separates them.
 *
 * shortcut: an image nested in a link or emphasis stays alt text; split those too if agents send badges.
 */
internal fun List<InlineNode>.imageSegments(): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    val text = mutableListOf<InlineNode>()

    fun flush() {
        val trimmed = text.dropWhile { it.isBreak() }.dropLastWhile { it.isBreak() }
        if (trimmed.any { !it.isBreak() && !(it is InlineNode.Text && it.literal.isBlank()) }) {
            segments += InlineSegment.Text(trimmed)
        }
        text.clear()
    }
    forEach { node ->
        if (node is InlineNode.Image) {
            flush()
            segments += InlineSegment.Image(node)
        } else {
            text += node
        }
    }
    flush()
    return segments
}

private fun InlineNode.isBreak(): Boolean = this is InlineNode.SoftBreak || this is InlineNode.HardBreak
