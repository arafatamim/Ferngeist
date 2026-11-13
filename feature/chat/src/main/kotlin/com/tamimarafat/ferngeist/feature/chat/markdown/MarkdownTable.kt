/*
 * Vendored from com.adamglin.compose.markdown:markdown-compose 0.1.5 (MIT, (c) 2026 Adam Glin).
 * Trimmed: links open through the platform handler (no click callback), and the column-width
 * entry point is folded into its one implementation. A table wider than the space it has scrolls
 * rather than squeezing its columns. See MarkdownBlocks.kt for why the vendor exists.
 */

package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adamglin.compose.markdown.core.model.BlockNode
import com.adamglin.compose.markdown.core.model.TableAlignment
import com.tamimarafat.ferngeist.core.common.ui.edgeFadeMask

@Composable
internal fun TableBlock(
    block: BlockNode.TableBlock,
    styles: MarkdownBlockStyles,
    modifier: Modifier = Modifier,
) {
    val rows = remember(block) { listOf(block.header) + block.rows }
    val columnCount = block.header.cells.size
    if (columnCount == 0) return

    val headerStyle = MarkdownTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val bodyStyle = MarkdownTheme.typography.bodyMedium
    val borderColor = MarkdownTheme.colors.borderMuted
    val cellModels =
        remember(block, styles) {
            rows.map { row ->
                row.cells.map { cell ->
                    cell.children.toAnnotatedString(styles.inline)
                }
            }
        }

    // The border is part of the scrolling content, not a frame around it: a frame that stays put
    // reads as the whole table, while a border clipped at the bubble edge shows there is more.
    // The mask erases the edges instead of painting the bubble color over them, so it works on any
    // bubble surface. The scroll's child is measured unbounded, so the width to fit is handed to
    // TableLayout instead of being read off its constraints.
    val framePadding = 10.dp
    // Measured, not guessed: a fixed dp floor shrinks the line as the user's text grows, while the
    // monospaced advance keeps a character count the same measure at every font scale.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val minColumnWidth =
        remember(bodyStyle, density, measurer) {
            with(density) {
                measurer
                    .measure("M".repeat(READABLE_COLUMNS), bodyStyle, maxLines = 1)
                    .size
                    .width
                    .toDp()
            }
        }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val scrollState = rememberScrollState()
        TableLayout(
            columnCount = columnCount,
            rowCount = rows.size,
            alignments = block.alignments,
            availableWidth = (maxWidth - framePadding * 2).coerceAtLeast(0.dp),
            minColumnWidth = minColumnWidth,
            modifier =
                Modifier
                    .edgeFadeMask(scrollState = scrollState, orientation = Orientation.Horizontal)
                    .horizontalScroll(scrollState)
                    .border(
                        width = 1.dp,
                        color = borderColor.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(10.dp),
                    ).padding(framePadding),
            divider = {
                MarkdownDivider(color = borderColor)
            },
        ) { rowIndex, columnIndex, alignment ->
            val model = cellModels[rowIndex][columnIndex]
            val baseStyle = if (rowIndex == 0) headerStyle else bodyStyle
            // Prefer content-sized measure: skip fillMaxWidth so preferred widths stay honest.
            MarkdownInlineText(
                text = model,
                style = baseStyle.copy(textAlign = alignment.toTextAlign()),
                fillMaxWidth = false,
            )
        }
    }
}

/**
 * The narrowest a column may be squeezed to, in characters of the table's body face. Below it a
 * cell breaks words into slivers, so the table keeps the width and scrolls instead — the floor is
 * what makes that decision about the content, not about how wide the container happens to be.
 */
private const val READABLE_COLUMNS = 20

internal fun tableCellHorizontalOffset(
    alignment: TableAlignment,
    columnWidth: Int,
    childWidth: Int,
): Int {
    val free = (columnWidth - childWidth).coerceAtLeast(0)
    return when (alignment) {
        TableAlignment.Center -> free / 2
        TableAlignment.Right -> free
        TableAlignment.Default, TableAlignment.Left -> 0
    }
}

private fun TableAlignment.toTextAlign(): TextAlign =
    when (this) {
        TableAlignment.Center -> TextAlign.Center
        TableAlignment.Right -> TextAlign.End
        TableAlignment.Default, TableAlignment.Left -> TextAlign.Start
    }
