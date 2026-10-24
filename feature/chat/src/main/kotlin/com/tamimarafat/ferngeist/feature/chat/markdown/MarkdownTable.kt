/*
 * Vendored from com.adamglin.compose.markdown:markdown-compose 0.1.5 (MIT, (c) 2026 Adam Glin).
 * Trimmed: links open through the platform handler (no click callback), and the column-width
 * entry point is folded into its one implementation. See MarkdownBlocks.kt for why the vendor exists.
 */

package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adamglin.compose.markdown.core.model.BlockNode
import com.adamglin.compose.markdown.core.model.TableAlignment

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

    TableLayout(
        columnCount = columnCount,
        rowCount = rows.size,
        alignments = block.alignments,
        modifier =
            modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = borderColor.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(10.dp),
                ).padding(10.dp),
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
