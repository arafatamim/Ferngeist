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
