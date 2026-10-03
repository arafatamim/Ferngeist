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
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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

// Vendored: SubcomposeLayout measure/place arithmetic. Suppressed so this file stays
// diffable against upstream.
@Suppress("CyclomaticComplexMethod")
@Composable
private fun TableLayout(
    columnCount: Int,
    rowCount: Int,
    alignments: List<TableAlignment>,
    modifier: Modifier = Modifier,
    columnSpacing: Dp = 8.dp,
    rowSpacing: Dp = 6.dp,
    divider: @Composable () -> Unit,
    cell: @Composable (rowIndex: Int, columnIndex: Int, alignment: TableAlignment) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val columnSpacingPx = columnSpacing.roundToPx()
        val rowSpacingPx = rowSpacing.roundToPx()
        val totalColumnSpacing = columnSpacingPx * (columnCount - 1).coerceAtLeast(0)
        val resolvedAlignments =
            Array(columnCount) { columnIndex ->
                alignments.getOrElse(columnIndex) { TableAlignment.Default }
            }

        val preferredCells: Array<Array<Placeable>> =
            Array(rowCount) { rowIndex ->
                Array(columnCount) { columnIndex ->
                    subcompose("pref_${rowIndex}_$columnIndex") {
                        cell(rowIndex, columnIndex, resolvedAlignments[columnIndex])
                    }.first().measure(Constraints())
                }
            }

        val preferredWidths =
            IntArray(columnCount) { columnIndex ->
                var maxWidth = 0
                for (rowIndex in 0 until rowCount) {
                    maxWidth = maxOf(maxWidth, preferredCells[rowIndex][columnIndex].width)
                }
                maxWidth
            }
        val availableWidth =
            if (constraints.hasBoundedWidth) {
                (constraints.maxWidth - totalColumnSpacing).coerceAtLeast(0)
            } else {
                preferredWidths.sum()
            }
        val columnWidths = distributeTableColumnWidths(preferredWidths, availableWidth)

        val cells: Array<Array<Placeable>> =
            Array(rowCount) { rowIndex ->
                Array(columnCount) { columnIndex ->
                    val preferred = preferredCells[rowIndex][columnIndex]
                    if (preferred.width <= columnWidths[columnIndex]) {
                        preferred
                    } else {
                        subcompose("cell_${rowIndex}_$columnIndex") {
                            cell(rowIndex, columnIndex, resolvedAlignments[columnIndex])
                        }.first().measure(Constraints(maxWidth = columnWidths[columnIndex]))
                    }
                }
            }

        val layoutWidth =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                (columnWidths.sum() + totalColumnSpacing)
                    .coerceIn(constraints.minWidth, constraints.maxWidth)
            }
        // Match previous library visuals: a single rule under the header row.
        val headerDivider =
            if (rowCount > 0) {
                subcompose("divider_header") {
                    divider()
                }.first().measure(Constraints.fixedWidth(layoutWidth))
            } else {
                null
            }
        val rowHeights =
            IntArray(rowCount) { rowIndex ->
                var maxHeight = 0
                for (columnIndex in 0 until columnCount) {
                    maxHeight = maxOf(maxHeight, cells[rowIndex][columnIndex].height)
                }
                maxHeight
            }
        var layoutHeight = 0
        for (rowIndex in 0 until rowCount) {
            layoutHeight += rowHeights[rowIndex]
            if (rowIndex == 0 && headerDivider != null) {
                layoutHeight += rowSpacingPx + headerDivider.height
            }
            if (rowIndex != rowCount - 1) {
                layoutHeight += rowSpacingPx
            }
        }

        layout(
            width = layoutWidth.coerceIn(constraints.minWidth, constraints.maxWidth),
            height = layoutHeight.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            val columnOffsets = IntArray(columnCount)
            var x = 0
            for (columnIndex in 0 until columnCount) {
                columnOffsets[columnIndex] = x
                x += columnWidths[columnIndex] + columnSpacingPx
            }

            var y = 0
            for (rowIndex in 0 until rowCount) {
                for (columnIndex in 0 until columnCount) {
                    val placeable = cells[rowIndex][columnIndex]
                    placeable.placeRelative(
                        x =
                            columnOffsets[columnIndex] +
                                tableCellHorizontalOffset(
                                    alignment = resolvedAlignments[columnIndex],
                                    columnWidth = columnWidths[columnIndex],
                                    childWidth = placeable.width,
                                ),
                        y = y,
                    )
                }
                y += rowHeights[rowIndex]
                if (rowIndex == 0 && headerDivider != null) {
                    y += rowSpacingPx
                    headerDivider.placeRelative(0, y)
                    y += headerDivider.height
                }
                if (rowIndex != rowCount - 1) {
                    y += rowSpacingPx
                }
            }
        }
    }
}

@Suppress("ReturnCount")
internal fun distributeTableColumnWidths(
    preferredWidths: IntArray,
    availableWidth: Int,
): IntArray {
    if (preferredWidths.isEmpty()) return IntArray(0)
    if (availableWidth <= 0) return IntArray(preferredWidths.size)

    val totalPreferred = preferredWidths.sum()
    if (totalPreferred == 0) {
        val base = availableWidth / preferredWidths.size
        val widths = IntArray(preferredWidths.size) { base }
        widths[widths.lastIndex] += availableWidth - base * preferredWidths.size
        return widths
    }
    if (totalPreferred <= availableWidth) {
        val widths = preferredWidths.copyOf()
        widths[widths.lastIndex] += availableWidth - totalPreferred
        return widths
    }

    val maxPreferred = preferredWidths.max()
    var low = 0
    var high = maxPreferred
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        val cappedSum = preferredWidths.sumOf { minOf(it, mid).toLong() }
        if (cappedSum <= availableWidth) {
            low = mid
        } else {
            high = mid - 1
        }
    }
    val cap = low
    val widths = IntArray(preferredWidths.size) { index -> minOf(preferredWidths[index], cap) }
    var leftover = availableWidth - widths.sum()
    if (leftover > 0) {
        val expandable =
            preferredWidths.indices
                .filter { widths[it] < preferredWidths[it] }
                .sortedByDescending { preferredWidths[it] }
        var cursor = 0
        while (leftover > 0 && expandable.isNotEmpty()) {
            val index = expandable[cursor % expandable.size]
            if (widths[index] < preferredWidths[index]) {
                widths[index]++
                leftover--
            }
            cursor++
            if (cursor > expandable.size * (availableWidth + 1)) break
        }
        if (leftover > 0) {
            widths[widths.lastIndex] += leftover
        }
    }
    return widths
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
