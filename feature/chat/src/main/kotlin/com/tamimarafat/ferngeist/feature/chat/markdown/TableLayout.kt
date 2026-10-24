package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.compose.markdown.core.model.TableAlignment

// Vendored: SubcomposeLayout measure/place arithmetic, kept close to upstream so it stays diffable.
@Composable
internal fun TableLayout(
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
        val preferredCells = measurePreferredCells(rowCount, columnCount, resolvedAlignments, cell)
        val preferredWidths = IntArray(columnCount) { columnIndex -> maxWidthOfColumn(preferredCells, columnIndex) }
        val availableWidth =
            if (constraints.hasBoundedWidth) {
                (constraints.maxWidth - totalColumnSpacing).coerceAtLeast(0)
            } else {
                preferredWidths.sum()
            }
        val columnWidths = distributeTableColumnWidths(preferredWidths, availableWidth)
        val cells = measureTableCells(preferredCells, columnWidths, resolvedAlignments, cell)
        val layoutWidth =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                (columnWidths.sum() + totalColumnSpacing)
                    .coerceIn(constraints.minWidth, constraints.maxWidth)
            }
        val headerDivider = measureHeaderDivider(rowCount, divider, layoutWidth)
        val rowHeights = IntArray(rowCount) { rowIndex -> maxHeightOfRow(cells, rowIndex) }
        val table =
            MeasuredTable(
                cells = cells,
                columnWidths = columnWidths,
                rowHeights = rowHeights,
                alignments = resolvedAlignments,
                headerDivider = headerDivider,
                spacing = TableSpacing(column = columnSpacingPx, row = rowSpacingPx),
            )
        val layoutHeight = tableHeight(table)
        layout(
            width = layoutWidth.coerceIn(constraints.minWidth, constraints.maxWidth),
            height = layoutHeight.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            placeTable(table)
        }
    }
}

/** A table measured for one pass: every cell at its final column width, ready to place. */
private class MeasuredTable(
    val cells: Array<Array<Placeable>>,
    val columnWidths: IntArray,
    val rowHeights: IntArray,
    val alignments: Array<TableAlignment>,
    val headerDivider: Placeable?,
    val spacing: TableSpacing,
)

/** Gaps between columns and between rows, in pixels. */
private class TableSpacing(
    val column: Int,
    val row: Int,
)

/** Each cell at its preferred size, measured unconstrained, so the column widths can be worked out. */
private fun SubcomposeMeasureScope.measurePreferredCells(
    rowCount: Int,
    columnCount: Int,
    alignments: Array<TableAlignment>,
    cell: @Composable (rowIndex: Int, columnIndex: Int, alignment: TableAlignment) -> Unit,
): Array<Array<Placeable>> =
    Array(rowCount) { rowIndex ->
        Array(columnCount) { columnIndex ->
            subcompose("pref_${rowIndex}_$columnIndex") {
                cell(rowIndex, columnIndex, alignments[columnIndex])
            }.first().measure(Constraints())
        }
    }

/** Cells that already fit their column keep their preferred measurement; the rest are re-measured to the column. */
private fun SubcomposeMeasureScope.measureTableCells(
    preferredCells: Array<Array<Placeable>>,
    columnWidths: IntArray,
    alignments: Array<TableAlignment>,
    cell: @Composable (rowIndex: Int, columnIndex: Int, alignment: TableAlignment) -> Unit,
): Array<Array<Placeable>> =
    Array(preferredCells.size) { rowIndex ->
        Array(columnWidths.size) { columnIndex ->
            val preferred = preferredCells[rowIndex][columnIndex]
            if (preferred.width <= columnWidths[columnIndex]) {
                preferred
            } else {
                subcompose("cell_${rowIndex}_$columnIndex") {
                    cell(rowIndex, columnIndex, alignments[columnIndex])
                }.first().measure(Constraints(maxWidth = columnWidths[columnIndex]))
            }
        }
    }

// Match previous library visuals: a single rule under the header row.
private fun SubcomposeMeasureScope.measureHeaderDivider(
    rowCount: Int,
    divider: @Composable () -> Unit,
    layoutWidth: Int,
): Placeable? =
    if (rowCount > 0) {
        subcompose("divider_header") {
            divider()
        }.first().measure(Constraints.fixedWidth(layoutWidth))
    } else {
        null
    }

private fun maxWidthOfColumn(
    cells: Array<Array<Placeable>>,
    columnIndex: Int,
): Int = cells.fold(0) { widest, row -> maxOf(widest, row[columnIndex].width) }

private fun maxHeightOfRow(
    cells: Array<Array<Placeable>>,
    rowIndex: Int,
): Int = cells[rowIndex].fold(0) { tallest, placeable -> maxOf(tallest, placeable.height) }

/** Rows and the spacing between them, with the header rule counted in after the first row. */
private fun tableHeight(table: MeasuredTable): Int {
    var layoutHeight = 0
    for (rowIndex in table.rowHeights.indices) {
        layoutHeight += table.rowHeights[rowIndex]
        if (rowIndex == 0 && table.headerDivider != null) {
            layoutHeight += table.spacing.row + table.headerDivider.height
        }
        if (rowIndex != table.rowHeights.size - 1) {
            layoutHeight += table.spacing.row
        }
    }
    return layoutHeight
}

private fun Placeable.PlacementScope.placeTable(table: MeasuredTable) {
    val columnOffsets = IntArray(table.columnWidths.size)
    var x = 0
    for (columnIndex in columnOffsets.indices) {
        columnOffsets[columnIndex] = x
        x += table.columnWidths[columnIndex] + table.spacing.column
    }

    var y = 0
    for (rowIndex in table.rowHeights.indices) {
        for (columnIndex in table.columnWidths.indices) {
            val placeable = table.cells[rowIndex][columnIndex]
            placeable.placeRelative(
                x =
                    columnOffsets[columnIndex] +
                        tableCellHorizontalOffset(
                            alignment = table.alignments[columnIndex],
                            columnWidth = table.columnWidths[columnIndex],
                            childWidth = placeable.width,
                        ),
                y = y,
            )
        }
        y += table.rowHeights[rowIndex]
        if (rowIndex == 0 && table.headerDivider != null) {
            y += table.spacing.row
            table.headerDivider.placeRelative(0, y)
            y += table.headerDivider.height
        }
        if (rowIndex != table.rowHeights.size - 1) {
            y += table.spacing.row
        }
    }
}

internal fun distributeTableColumnWidths(
    preferredWidths: IntArray,
    availableWidth: Int,
): IntArray {
    val totalPreferred = preferredWidths.sum()
    return when {
        preferredWidths.isEmpty() -> IntArray(0)
        availableWidth <= 0 -> IntArray(preferredWidths.size)
        totalPreferred == 0 -> evenWidths(preferredWidths.size, availableWidth)
        totalPreferred <= availableWidth ->
            preferredWidths.copyOf().also { it[it.lastIndex] += availableWidth - totalPreferred }
        else -> capToAvailable(preferredWidths, availableWidth)
    }
}

/** Splits [availableWidth] evenly, the remainder going to the last column. */
private fun evenWidths(
    count: Int,
    availableWidth: Int,
): IntArray {
    val base = availableWidth / count
    val widths = IntArray(count) { base }
    widths[widths.lastIndex] += availableWidth - base * count
    return widths
}

private fun capToAvailable(
    preferredWidths: IntArray,
    availableWidth: Int,
): IntArray {
    val cap = largestCapFitting(preferredWidths, availableWidth)
    val widths = IntArray(preferredWidths.size) { index -> minOf(preferredWidths[index], cap) }
    spreadLeftover(widths, preferredWidths, availableWidth, availableWidth - widths.sum())
    return widths
}

/** The largest per-column cap whose capped widths still fit [availableWidth]. */
private fun largestCapFitting(
    preferredWidths: IntArray,
    availableWidth: Int,
): Int {
    var low = 0
    var high = preferredWidths.max()
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        val cappedSum = preferredWidths.sumOf { minOf(it, mid).toLong() }
        if (cappedSum <= availableWidth) {
            low = mid
        } else {
            high = mid - 1
        }
    }
    return low
}

/** Hands the leftover pixels one at a time to the columns the cap held back, widest first. */
private fun spreadLeftover(
    widths: IntArray,
    preferredWidths: IntArray,
    availableWidth: Int,
    leftover: Int,
) {
    if (leftover <= 0) return
    val expandable =
        preferredWidths.indices
            .filter { widths[it] < preferredWidths[it] }
            .sortedByDescending { preferredWidths[it] }
    var remaining = leftover
    var cursor = 0
    while (remaining > 0 && expandable.isNotEmpty()) {
        val index = expandable[cursor % expandable.size]
        if (widths[index] < preferredWidths[index]) {
            widths[index]++
            remaining--
        }
        cursor++
        if (cursor > expandable.size * (availableWidth + 1)) break
    }
    if (remaining > 0) {
        widths[widths.lastIndex] += remaining
    }
}
