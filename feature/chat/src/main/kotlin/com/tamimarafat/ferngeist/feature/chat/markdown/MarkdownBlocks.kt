/*
 * Vendored from com.adamglin.compose.markdown:markdown-compose 0.1.5 (MIT, (c) 2026 Adam Glin).
 *
 * Modified from upstream in four functional ways: every block takes an optional [BlockReveal], so
 * a streaming run shows only what its reveal cursor has reached and fades the leading edge in (see
 * MarkdownReveal.kt and docs/adr/0001); paragraphs and headings draw [localInlineChips]' matches as
 * inline content once their reveal settles (see InlineChips.kt); paragraphs lay their top-level
 * images out between their text when [localTranscriptImages] can load them; and [MarkdownBlocksLazy]
 * renders a whole document for a screen that owns the scroll. Upstream keeps every
 * block renderer private and offers no per-block hook, so there was no way to do that without copying.
 */

package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.compose.markdown.core.model.BlockNode
import com.adamglin.compose.markdown.core.model.InlineNode
import com.adamglin.compose.markdown.core.model.ListType
import com.adamglin.compose.markdown.core.model.TaskState
import com.tamimarafat.ferngeist.core.common.ui.localSyntaxColors
import com.tamimarafat.ferngeist.core.git.syntax.SyntaxLanguageResolver
import com.tamimarafat.ferngeist.core.git.syntax.rememberHighlightedCode
import com.tamimarafat.ferngeist.core.git.syntax.styledCode
import com.tamimarafat.ferngeist.feature.chat.artifact.ui.ParagraphAttachments
import com.tamimarafat.ferngeist.feature.chat.markdown.image.MarkdownImage
import com.tamimarafat.ferngeist.feature.chat.markdown.image.localTranscriptImages
import com.tamimarafat.ferngeist.feature.chat.selection.QuoteSelectionContainer

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
        QuoteSelectionContainer {
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
 * Renders a whole document in a lazy list, for a screen that scrolls it as its own page.
 *
 * Chat bubbles keep [MarkdownBlocks]' column: they live inside the transcript's list, and a list
 * inside a list would fight it for the scroll. A file preview owns the sheet, and a README can
 * hold thousands of blocks, so composing only the visible ones is the difference between a
 * readable file and a stall.
 */
@Composable
internal fun MarkdownBlocksLazy(
    blocks: List<BlockNode>,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    footer: (@Composable () -> Unit)? = null,
) {
    ProvideMarkdownTheme(typography) {
        val styles = rememberMarkdownBlockStyles()
        SelectionContainer {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                state = state,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(blocks, key = { it.id.raw }) { block ->
                    MarkdownBlock(
                        block = block,
                        styles = styles,
                    )
                }
                if (footer != null) {
                    item { footer() }
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
    content: @Composable (T, Int, BlockReveal?) -> Unit,
) {
    var offset = reveal?.offset ?: 0
    children.forEachIndexed { index, child ->
        val childReveal = reveal?.copy(offset = offset)
        if (reveal != null) offset += child.revealLength()
        RevealGate(childReveal) { content(child, index, childReveal) }
    }
}

// Vendored: one branch per BlockNode subtype, split in two so each stays within the complexity
// limit and this file stays diffable against upstream.
@Composable
private fun MarkdownBlock(
    block: BlockNode,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
    reveal: BlockReveal? = null,
) {
    when (block) {
        is BlockNode.BlockQuote ->
            QuoteBlock(
                block = block,
                styles = styles,
                modifier = modifier,
                reveal = reveal,
            )

        is BlockNode.FencedCodeBlock ->
            CodeBlock(
                literal = block.literal,
                languageHint = block.languageHint,
                infoString = block.infoString,
                isClosed = block.isClosed,
                styles = styles,
                modifier = modifier,
                reveal = reveal,
            )

        is BlockNode.Heading -> MarkdownHeading(block, styles, modifier, reveal)

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

        // List items are rendered by their ListBlock, which owns the marker column.
        is BlockNode.ListItem -> Unit

        else -> MarkdownLeafBlock(block = block, styles = styles, modifier = modifier, reveal = reveal)
    }
}

/** The blocks that hold text or a rule and no child blocks; see [MarkdownBlock] for the rest. */
@Composable
private fun MarkdownLeafBlock(
    block: BlockNode,
    styles: MarkdownBlockStyles,
    modifier: Modifier,
    reveal: BlockReveal?,
) {
    when (block) {
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

        is BlockNode.Paragraph -> ParagraphBlock(block, styles, modifier, reveal)

        is BlockNode.IndentedCodeBlock ->
            CodeBlock(
                literal = block.literal,
                languageHint = null,
                infoString = null,
                isClosed = true,
                styles = styles,
                modifier = modifier,
                reveal = reveal,
            )

        is BlockNode.ThematicBreak -> {
            val alpha = rememberAtomicRevealAlpha(reveal)
            MarkdownDivider(
                modifier = modifier.padding(vertical = 4.dp).graphicsLayer { this.alpha = alpha.value },
                color = MarkdownTheme.colors.borderMuted,
                thickness = 1.dp,
            )
        }

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
        else -> error("${block::class.simpleName} is not a leaf block")
    }
}

/** A paragraph, then the files it references as cards once the paragraph has ended. */
@Composable
private fun ParagraphBlock(
    block: BlockNode.Paragraph,
    styles: MarkdownBlockStyles,
    modifier: Modifier,
    reveal: BlockReveal?,
) {
    val images = localTranscriptImages.current
    // Per paragraph rather than the run's settle, which flips with every pause in the stream.
    val ended by remember(reveal, block) { derivedStateOf { reveal == null || reveal.hasEnded(block.revealLength()) } }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (images == null || block.children.none { it is InlineNode.Image }) {
            RevealInlines(block.children, styles, Modifier, reveal)
        } else {
            val segments = remember(block.children) { block.children.imageSegments() }
            var offset = reveal?.offset ?: 0
            segments.forEach { segment ->
                val segmentReveal = reveal?.copy(offset = offset)
                if (reveal != null) offset += segment.revealLength()
                RevealGate(segmentReveal) {
                    when (segment) {
                        is InlineSegment.Text -> RevealInlines(segment.nodes, styles, Modifier, segmentReveal)
                        is InlineSegment.Image -> {
                            val alpha = rememberAtomicRevealAlpha(segmentReveal)
                            MarkdownImage(
                                destination = segment.node.destination,
                                alt =
                                    segment.node.alt
                                        .toAnnotatedString(styles.inline)
                                        .text,
                                loader = images,
                                fallbackStyle =
                                    MarkdownTheme.typography.bodyLarge.copy(
                                        color = MarkdownTheme.colors.textSecondary,
                                    ),
                                modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
                            )
                        }
                    }
                }
            }
        }
        ParagraphAttachments(block.children, ended = ended)
    }
}

@Composable
private fun QuoteBlock(
    block: BlockNode.BlockQuote,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
    reveal: BlockReveal? = null,
) {
    val bar = MarkdownTheme.colors.accent.copy(alpha = 0.55f)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MarkdownTheme.colors.surfaceMuted.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                ).padding(12.dp)
                // Drawn, not measured: a full-height bar sized by intrinsics crashes on the
                // BoxWithConstraints inside quoted paragraphs and tables.
                .drawBehind {
                    val width = 4.dp.toPx()
                    drawRoundRect(bar, size = Size(width, size.height), cornerRadius = CornerRadius(width / 2))
                }.padding(start = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RevealChildren(block.children, reveal) { child, _, childReveal ->
            MarkdownBlock(
                block = child,
                styles = styles,
                reveal = childReveal,
            )
        }
    }
}

@Composable
private fun CodeBlock(
    literal: String,
    languageHint: String?,
    infoString: String?,
    isClosed: Boolean,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
    reveal: BlockReveal? = null,
) {
    val language = SyntaxLanguageResolver.fromFence(languageHint ?: infoString)
    val highlighted = rememberHighlightedCode(literal, language, streaming = !isClosed)
    val colors = localSyntaxColors.current
    val annotatedCode =
        remember(literal, highlighted, language, colors) {
            styledCode(literal, highlighted, language, colors)
        }

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
        if (!infoString.isNullOrBlank() || !isClosed) {
            MarkdownText(
                text =
                    buildString {
                        append(infoString ?: "code")
                        if (!isClosed) {
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
        RevealChildren(block.items, reveal) { item, index, itemReveal ->
            ListItemBlock(
                block = item,
                marker =
                    when (val type = block.type) {
                        is ListType.Bullet -> type.bulletChar.toString()
                        is ListType.Ordered -> "${type.startNumber + index}${type.delimiter}"
                    },
                styles = styles,
                reveal = itemReveal,
            )
        }
    }
}

@Composable
private fun ListItemBlock(
    block: BlockNode.ListItem,
    marker: String,
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
                text = marker,
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
            RevealChildren(block.children, reveal) { child, _, childReveal ->
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
