/*
 * Vendored from com.adamglin.compose.markdown:markdown-compose 0.1.5 (MIT, (c) 2026 Adam Glin).
 *
 * Modified from upstream in one functional way: every block takes an optional [BlockReveal], so a
 * streaming run shows only what its reveal cursor has reached and fades the leading edge in (see
 * MarkdownReveal.kt and docs/adr/0001). Upstream keeps every block renderer private and offers no
 * per-block hook, so there was no way to do that without copying.
 */

package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.compose.markdown.core.model.BlockNode
import com.adamglin.compose.markdown.core.model.InlineNode
import com.adamglin.compose.markdown.core.model.TaskState

/**
 * Renders parsed markdown blocks.
 *
 * @param reveal the run's reveal cursor while it is streaming; null renders everything at once.
 */
@Composable
internal fun MarkdownBlocks(
    blocks: List<BlockNode>,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier,
    reveal: RunReveal? = null,
) {
    ProvideMarkdownTheme(typography) {
        val styles = rememberMarkdownBlockStyles()
        SelectionContainer {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var offset = 0
                blocks.forEach { block ->
                    val blockReveal = reveal?.let { BlockReveal(it, offset) }
                    if (reveal != null) offset += block.revealLength()
                    key(block.id.raw) {
                        RevealGate(blockReveal) {
                            MarkdownBlock(
                                block = block,
                                styles = styles,
                                reveal = blockReveal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lays out [children] in reveal order: each gets its offset into the run, and one the cursor has
 * not reached is not composed, so it takes no space yet.
 */
@Composable
private fun <T : BlockNode> RevealChildren(
    children: List<T>,
    reveal: BlockReveal?,
    content: @Composable (T, BlockReveal?) -> Unit,
) {
    var offset = reveal?.offset ?: 0
    children.forEach { child ->
        val childReveal = reveal?.copy(offset = offset)
        if (reveal != null) offset += child.revealLength()
        RevealGate(childReveal) { content(child, childReveal) }
    }
}

// Vendored: one branch per BlockNode subtype. Suppressed rather than split so this file
// stays diffable against upstream.
@Suppress("CyclomaticComplexMethod")
@Composable
private fun MarkdownBlock(
    block: BlockNode,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
    reveal: BlockReveal? = null,
) {
    when (block) {
        is BlockNode.Document ->
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RevealChildren(block.children, reveal) { child, childReveal ->
                    MarkdownBlock(
                        block = child,
                        styles = styles,
                        reveal = childReveal,
                    )
                }
            }

        is BlockNode.BlockQuote ->
            QuoteBlock(
                block = block,
                styles = styles,
                modifier = modifier,
                reveal = reveal,
            )

        is BlockNode.FencedCodeBlock ->
            CodeBlock(
                block = block,
                styles = styles,
                modifier = modifier,
                reveal = reveal,
            )

        is BlockNode.Heading ->
            RevealText(
                text = block.children.toAnnotatedString(styles.inline),
                reveal = reveal,
                style =
                    when (block.level) {
                        1 -> MarkdownTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold)
                        2 -> MarkdownTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                        3 -> MarkdownTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                        4 -> MarkdownTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        5 -> MarkdownTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        else -> MarkdownTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    },
                modifier = modifier.fillMaxWidth(),
            )

        is BlockNode.ListBlock ->
            ListBlock(
                block = block,
                styles = styles,
                modifier = modifier,
                reveal = reveal,
            )

        is BlockNode.TableBlock -> {
            // Tables render whole: a half-revealed grid reflows every column as cells fill.
            val alpha = rememberAtomicRevealAlpha(reveal)
            TableBlock(
                block = block,
                styles = styles,
                modifier = modifier.graphicsLayer { this.alpha = alpha.value },
            )
        }

        is BlockNode.TableRow ->
            MarkdownText(
                text =
                    block.cells
                        .joinToString(separator = " | ") { cell ->
                            cell.children.toAnnotatedString(styles.inline).text
                        }.let(::AnnotatedString),
                style = MarkdownTheme.typography.bodyMedium,
                modifier = modifier,
            )

        is BlockNode.TableCell ->
            MarkdownInlineText(
                text = block.children.toAnnotatedString(styles.inline),
                style = MarkdownTheme.typography.bodyMedium,
                modifier = modifier,
            )

        is BlockNode.Paragraph ->
            RevealText(
                text = block.children.toAnnotatedString(styles.inline),
                style = MarkdownTheme.typography.bodyLarge,
                reveal = reveal,
                modifier = modifier.fillMaxWidth(),
            )

        is BlockNode.RawTextBlock ->
            RevealText(
                text = AnnotatedString(block.literal),
                style = MarkdownTheme.typography.bodyLarge,
                reveal = reveal,
                modifier = modifier.fillMaxWidth(),
            )

        is BlockNode.ThematicBreak -> {
            val alpha = rememberAtomicRevealAlpha(reveal)
            MarkdownDivider(
                modifier = modifier.padding(vertical = 4.dp).graphicsLayer { this.alpha = alpha.value },
                color = MarkdownTheme.colors.borderMuted,
                thickness = 1.dp,
            )
        }

        is BlockNode.UnsupportedBlock ->
            RevealText(
                text = AnnotatedString(block.literal),
                style = MarkdownTheme.typography.bodyLarge,
                reveal = reveal,
                modifier = modifier.fillMaxWidth(),
            )

        is BlockNode.ListItem ->
            ListItemBlock(
                block = block,
                styles = styles,
                modifier = modifier,
                reveal = reveal,
            )

        // The "streaming..." suffix sits past the reveal length, so it stays hidden while revealing.
        is BlockNode.MathBlock ->
            RevealText(
                text =
                    AnnotatedString(
                        buildString {
                            append(block.latex)
                            if (!block.isClosed) {
                                append("  streaming...")
                            }
                        },
                    ),
                style = MarkdownTheme.typography.bodyLarge,
                reveal = reveal,
                modifier = modifier.fillMaxWidth(),
            )
    }
}

@Composable
private fun QuoteBlock(
    block: BlockNode.BlockQuote,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
    reveal: BlockReveal? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(
                    color = MarkdownTheme.colors.surfaceMuted.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                ).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        color = MarkdownTheme.colors.accent.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(999.dp),
                    ).align(Alignment.Top)
                    .padding(vertical = 4.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RevealChildren(block.children, reveal) { child, childReveal ->
                MarkdownBlock(
                    block = child,
                    styles = styles,
                    reveal = childReveal,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(
    block: BlockNode.FencedCodeBlock,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
    reveal: BlockReveal? = null,
) {
    val annotatedCode = remember(block.literal) { AnnotatedString(block.literal) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MarkdownTheme.colors.surfaceMuted.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                ).border(
                    width = 1.dp,
                    color = MarkdownTheme.colors.borderMuted.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(14.dp),
                ).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!block.infoString.isNullOrBlank() || !block.isClosed) {
            MarkdownText(
                text =
                    buildString {
                        append(block.infoString ?: "code")
                        if (!block.isClosed) {
                            append("  streaming...")
                        }
                    },
                style =
                    MarkdownTheme.typography.labelMedium.copy(
                        color = MarkdownTheme.colors.textSecondary,
                    ),
            )
        }

        // Cut per line, not per word: code reflowing mid-line reads as the code changing.
        RevealText(
            text = annotatedCode,
            style = styles.codeBlockTextStyle,
            reveal = reveal,
            modifier = Modifier.fillMaxWidth(),
            byLine = true,
        )
    }
}

@Composable
private fun ListBlock(
    block: BlockNode.ListBlock,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
    reveal: BlockReveal? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (block.isLoose) 10.dp else 6.dp),
    ) {
        RevealChildren(block.items, reveal) { item, itemReveal ->
            ListItemBlock(
                block = item,
                styles = styles,
                reveal = itemReveal,
            )
        }
    }
}

@Composable
private fun ListItemBlock(
    block: BlockNode.ListItem,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
    reveal: BlockReveal? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val taskState = block.taskState
        if (taskState != null) {
            TaskListMarker(taskState = taskState)
        } else {
            MarkdownText(
                text = block.marker,
                style = MarkdownTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier =
                    Modifier
                        .padding(top = 1.dp)
                        .alignBy { it.measuredHeight / 2 },
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RevealChildren(block.children, reveal) { child, childReveal ->
                MarkdownBlock(
                    block = child,
                    styles = styles,
                    reveal = childReveal,
                )
            }
        }
    }
}

@Composable
private fun TaskListMarker(
    taskState: TaskState,
    modifier: Modifier = Modifier,
) {
    val isChecked = taskState == TaskState.Checked
    val borderColor = if (isChecked) MarkdownTheme.colors.accent else MarkdownTheme.colors.border
    val fillColor = if (isChecked) MarkdownTheme.colors.accent.copy(alpha = 0.14f) else Color.Transparent

    Box(
        modifier =
            modifier
                .padding(top = 3.dp)
                .size(18.dp)
                .border(
                    width = 1.5.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(4.dp),
                ).background(
                    color = fillColor,
                    shape = RoundedCornerShape(4.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (isChecked) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(4.dp),
            ) {
                val strokeWidth = size.minDimension * 0.18f
                drawLine(
                    color = borderColor,
                    start = Offset(x = size.width * 0.14f, y = size.height * 0.54f),
                    end = Offset(x = size.width * 0.4f, y = size.height * 0.8f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = borderColor,
                    start = Offset(x = size.width * 0.4f, y = size.height * 0.8f),
                    end = Offset(x = size.width * 0.86f, y = size.height * 0.2f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun MarkdownText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style = style,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun MarkdownText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style = style,
        modifier = modifier,
    )
}

@Composable
internal fun MarkdownDivider(
    color: Color,
    modifier: Modifier = Modifier,
    thickness: androidx.compose.ui.unit.Dp = 1.dp,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(thickness)
                .background(color = color),
    )
}

@Stable
internal data class MarkdownBlockStyles(
    val inline: MarkdownInlineStyles,
    val codeBlockTextStyle: TextStyle,
)

@Stable
internal data class MarkdownInlineStyles(
    val emphasis: SpanStyle,
    val strong: SpanStyle,
    val strike: SpanStyle,
    val code: SpanStyle,
    val link: TextLinkStyles,
)

@Composable
internal fun rememberMarkdownBlockStyles(): MarkdownBlockStyles {
    val colors = MarkdownTheme.colors
    val typography = MarkdownTheme.typography

    return remember(colors, typography) {
        MarkdownBlockStyles(
            inline =
                MarkdownInlineStyles(
                    emphasis = SpanStyle(fontStyle = FontStyle.Italic),
                    strong = SpanStyle(fontWeight = FontWeight.Bold),
                    strike = SpanStyle(textDecoration = TextDecoration.LineThrough),
                    code =
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colors.surfaceMuted.copy(alpha = 0.75f),
                            fontSize = typography.bodyLarge.fontSize,
                        ),
                    link =
                        TextLinkStyles(
                            style =
                                SpanStyle(
                                    color = colors.accent,
                                    textDecoration = TextDecoration.Underline,
                                ),
                        ),
                ),
            codeBlockTextStyle =
                typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp,
                ),
        )
    }
}

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
): AnnotatedString =
    buildAnnotatedString {
        appendInlineNodes(nodes = this@toAnnotatedString, styles = styles)
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
) {
    nodes.forEach { node ->
        when (node) {
            is InlineNode.CodeSpan ->
                withStyle(styles.code) {
                    append(node.literal)
                }

            is InlineNode.Emphasis ->
                withStyle(styles.emphasis) {
                    appendInlineNodes(node.children, styles)
                }

            is InlineNode.HardBreak -> append("\n")

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
                    appendInlineNodes(node.children, styles)
                }

            is InlineNode.Strong ->
                withStyle(styles.strong) {
                    appendInlineNodes(node.children, styles)
                }

            is InlineNode.Text -> append(node.literal)

            is InlineNode.UnsupportedInline -> append(node.literal)

            // ponytail: math renders as its raw source. No LaTeX renderer is wired up; swap in a
            // MathRenderer only if an agent actually emits $$ math in chat.
            is InlineNode.MathSpan -> append(node.latex)
        }
    }
}
