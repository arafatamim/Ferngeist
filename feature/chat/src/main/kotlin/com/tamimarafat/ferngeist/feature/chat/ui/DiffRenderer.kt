package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.common.ui.LocalGitSemanticColors
import io.github.diff.DeltaType
import io.github.diff.generatePatch

private const val MAX_DIFF_ROWS = 500

/**
 * Renders a unified-diff view for a single file change.
 *
 * Computes the diff between [diff.oldText] and [diff.newText] using the
 * java-diff-utils patch generator, then displays the file path header
 * followed by color-coded insert/delete/equal lines.
 *
 * @param diff The tool call diff containing old and new text content.
 */
@Composable
internal fun DiffRenderer(diff: ToolCallContent.Diff) {
    val rows = remember(diff.oldText, diff.newText) {
        val oldLines = diff.oldText?.lines() ?: emptyList()
        val newLines = diff.newText.lines()
        val result = mutableListOf<LineDiffRow>()

        if (oldLines.isEmpty()) {
            // Entirely new file — all lines are insertions.
            newLines.forEach { line -> result.add(LineDiffRow.Insert(line)) }
        } else {
            val patch = generatePatch {
                original = oldLines
                revised = newLines
            }

            // Walk through old/new arrays emitting equals, deletes, and inserts.
            var oldPos = 0
            var newPos = 0

            for (delta in patch.getDeltas()) {
                val sourceChunk = delta.source
                val targetChunk = delta.target
                // Emit any equal lines that precede this delta chunk.
                val equalCount = sourceChunk.position - oldPos
                for (i in 0 until equalCount) {
                    result.add(LineDiffRow.Equal(oldLines[oldPos + i]))
                }
                oldPos = sourceChunk.position
                newPos += equalCount

                when (delta.type) {
                    DeltaType.DELETE -> {
                        for (line in sourceChunk.lines) {
                            result.add(LineDiffRow.Delete(line))
                        }
                        oldPos += sourceChunk.lines.size
                    }
                    DeltaType.INSERT -> {
                        for (line in targetChunk.lines) {
                            result.add(LineDiffRow.Insert(line))
                        }
                        newPos += targetChunk.lines.size
                    }
                    // A change is rendered as delete-then-insert.
                    DeltaType.CHANGE -> {
                        for (line in sourceChunk.lines) {
                            result.add(LineDiffRow.Delete(line))
                        }
                        for (line in targetChunk.lines) {
                            result.add(LineDiffRow.Insert(line))
                        }
                        oldPos += sourceChunk.lines.size
                        newPos += targetChunk.lines.size
                    }
                    // EQUAL deltas handled by positional emit before each non-equal delta — no per-delta action needed.
                    DeltaType.EQUAL -> {}
                }
            }

            // Emit any trailing equal lines after the last delta.
            for (i in oldPos until oldLines.size) {
                result.add(LineDiffRow.Equal(oldLines[i]))
            }
        }

        // Cap rows to prevent excessive layout work for huge diffs.
        if (result.size > MAX_DIFF_ROWS) {
            val truncated = result.take(MAX_DIFF_ROWS).toMutableList()
            truncated.add(LineDiffRow.Equal("… ${result.size - MAX_DIFF_ROWS} more lines"))
            truncated
        } else {
            result
        }
    }

    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = diff.path,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
                softWrap = false,
                overflow = TextOverflow.Visible,
            )

            rows.forEach { row ->
                val (prefix, bgColor, textColor) = when (row) {
                    is LineDiffRow.Delete -> Triple(
                        "- ",
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.error,
                    )
                    is LineDiffRow.Insert -> Triple(
                        "+ ",
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    // MaterialTheme lacks this particular green; hardcode instead of adding a theme color for a single use.
                        Color(0xFF43A047),
                    )
                    is LineDiffRow.Equal -> Triple(
                        "  ",
                        Color.Transparent,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "$prefix${row.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = textColor,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

/**
 * Result of proportionally mapping additions and deletions onto a fixed
 * number of visual blocks (5 total).
 *
 * @property green Number of green blocks representing added lines.
 * @property red Number of red blocks representing deleted lines.
 * @property empty Number of unfilled blocks padding to the total of 5.
 */
internal data class DiffBlockResult(
    val green: Int,
    val red: Int,
    val empty: Int,
)

/**
 * Distributes [additions] and [deletions] across 5 visual blocks
 * proportional to their relative counts.
 *
 * Guarantees at least one green block when [additions] > 0 and at least
 * one red block when [deletions] > 0, even when the proportion would
 * otherwise round to zero.
 *
 * @param additions The number of added lines.
 * @param deletions The number of deleted lines.
 * @return A [DiffBlockResult] with green, red, and empty block counts.
 */
internal fun computeDiffBlocks(additions: Int, deletions: Int): DiffBlockResult {
    val total = additions + deletions
    if (total == 0) return DiffBlockResult(0, 0, 5)

    // Fixed block count: small enough to fit in a row, enough blocks to show proportion.
    val totalBlocks = 5

    var green = kotlin.math.round(additions.toFloat() / total * totalBlocks).toInt()
    var red = totalBlocks - green

    // When a non-zero count would round to zero blocks, force at least one so the visual isn't misleading.
    if (additions > 0 && green == 0) { green = 1; red -= 1 }
    if (deletions > 0 && red == 0) { red = 1; green -= 1 }

    return DiffBlockResult(green, red, 5 - green - red)
}

/**
 * Counts the total number of lines added in this diff.
 *
 * When [oldText] is empty, every line in [newText] is treated as an
 * addition. Otherwise computes the full patch and sums insert and
 * change delta target sizes.
 *
 * @return The total number of added lines.
 */
internal fun ToolCallContent.Diff.computeAdditions(): Int {
    val oldLines = oldText?.lines() ?: emptyList()
    val newLines = newText.lines()
    // Entirely new file — every new line is an addition.
    if (oldLines.isEmpty()) return newLines.size
    val patch = generatePatch {
        original = oldLines
        revised = newLines
    }
    return patch.getDeltas().sumOf { delta ->
        when (delta.type) {
            DeltaType.INSERT,
            DeltaType.CHANGE -> delta.target.lines.size
            else -> 0
        }
    }
}

/**
 * Counts the total number of lines deleted in this diff.
 *
 * When [oldText] is empty, returns 0 (no deletions possible). Otherwise
 * computes the full patch and sums delete and change delta source sizes.
 *
 * @return The total number of deleted lines.
 */
internal fun ToolCallContent.Diff.computeDeletions(): Int {
    val oldLines = oldText?.lines() ?: emptyList()
    val newLines = newText.lines()
    // New file has nothing to delete.
    if (oldLines.isEmpty()) return 0
    val patch = generatePatch {
        original = oldLines
        revised = newLines
    }
    return patch.getDeltas().sumOf { delta ->
        when (delta.type) {
            DeltaType.DELETE,
            DeltaType.CHANGE -> delta.source.lines.size
            else -> 0
        }
    }
}

internal data class DiffStats(val additions: Int, val deletions: Int)

internal fun List<ToolCallContent.Diff>.computeDiffStats(): DiffStats {
    var totalAdditions = 0
    var totalDeletions = 0
    for (diff in this) {
        val oldLines = diff.oldText?.lines() ?: emptyList()
        val newLines = diff.newText.lines()
        if (oldLines.isEmpty()) {
            totalAdditions += newLines.size
        } else {
            val patch = generatePatch {
                original = oldLines
                revised = newLines
            }
            for (delta in patch.getDeltas()) {
                when (delta.type) {
                    DeltaType.INSERT -> {
                        totalAdditions += delta.target.lines.size
                    }
                    DeltaType.DELETE -> {
                        totalDeletions += delta.source.lines.size
                    }
                    DeltaType.CHANGE -> {
                        totalAdditions += delta.target.lines.size
                        totalDeletions += delta.source.lines.size
                    }
                    DeltaType.EQUAL -> {}
                }
            }
        }
    }
    return DiffStats(totalAdditions, totalDeletions)
}

/**
 * Compact summary row aggregating all [ToolCallContent.Diff] entries
 * into a visual +N / diff-blocks / -N indicator.
 *
 * Filters [content] for diff items, sums their additions and deletions,
 * and renders the result as a single horizontal row. Renders nothing
 * when [content] is null, empty, or contains no [ToolCallContent.Diff] items.
 *
 * @param content The list of tool call content items to aggregate.
 * @param modifier Optional [Modifier] applied to the outer row.
 */
@Composable
internal fun DiffSummaryRow(
    content: List<ToolCallContent>?,
    modifier: Modifier = Modifier,
) {
    if (!content.isNullOrEmpty()) {
        val diffs = content.filterIsInstance<ToolCallContent.Diff>()
        if (diffs.isNotEmpty()) {
            val stats = remember(diffs) { diffs.computeDiffStats() }
            val totalAdds = stats.additions
            val totalDels = stats.deletions
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier,
            ) {
                if (totalAdds > 0) {
                    Text(
                        text = "+$totalAdds",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalGitSemanticColors.current.added,
                    )
                }
                DiffBlocks(
                    additions = totalAdds,
                    deletions = totalDels,
                )
                if (totalDels > 0) {
                    Text(
                        text = "-$totalDels",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Renders a row of colored blocks representing the proportion of
 * additions (green) to deletions (red) in a diff.
 *
 * The blocks are computed by [computeDiffBlocks] and capped at 5
 * total. Additions are shown as green boxes, deletions as red boxes,
 * and remaining slots as empty outlined boxes.
 *
 * @param additions The number of added lines.
 * @param deletions The number of deleted lines.
 * @param showEmpty Whether to render the empty placeholder boxes that pad the
 *   row to 5 blocks. Pass `false` when only the filled proportion should be shown.
 * @param modifier Optional [Modifier] applied to the row.
 */
@Composable
internal fun DiffBlocks(
    additions: Int,
    deletions: Int,
    showEmpty: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val gitColors = LocalGitSemanticColors.current
    val result = remember(additions, deletions) {
        computeDiffBlocks(additions, deletions)
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(result.green) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(gitColors.added),
            )
        }
        repeat(result.red) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(gitColors.deleted),
            )
        }
        if (showEmpty) {
            repeat(result.empty) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
    }
}

/**
 * Compact tappable indicator showing the git working-tree status (additions vs
 * deletions) using the same visual vocabulary as the edit tool-call diff summary
 * ([DiffBlocks] + "+N" / "-N" counts) and the same tonal-button style as the
 * connection status pill in the top bar — but wider, as a capsule instead of a circle.
 *
 * Renders nothing when [additions] and [deletions] are both zero (clean tree).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun GitStatusIndicatorButton(
    additions: Int,
    deletions: Int,
    branch: String?,
    changedFiles: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (additions == 0 && deletions == 0 && changedFiles == 0) return
    val label = "$additions added, $deletions deleted"
    val branchLabel = branch?.ifBlank { "no branch" } ?: "no branch"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(
                title = {
                    Text(branchLabel)
                },
                modifier = Modifier.widthIn(max = 240.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("$changedFiles file(s) changed")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+$additions",
                            color = LocalGitSemanticColors.current.added,
                        )
                        Text(
                            text = "-$deletions",
                            color = LocalGitSemanticColors.current.deleted,
                        )
                    }
                }
            }
        },
        state = rememberTooltipState(),
    ) {
        FilledTonalButton(
            onClick = onClick,
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 0.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier =
                modifier
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongPress,
                    )
                    .semantics {
                        contentDescription = "Git status"
                        stateDescription = label
                    },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (additions > 0) {
                    Text(
                        text = "+$additions",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalGitSemanticColors.current.added,
                    )
                }
                DiffBlocks(additions = additions, deletions = deletions)
                if (deletions > 0) {
                    Text(
                        text = "-$deletions",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalGitSemanticColors.current.deleted,
                    )
                }
            }
        }
    }
}

/**
 * Represents a single line in a unified diff output.
 *
 * Each subclass encodes the line type and its text content.
 *
 * @property text The line content (without the +/- prefix).
 */
internal sealed class LineDiffRow(val text: String) {
    class Delete(text: String) : LineDiffRow(text)
    class Insert(text: String) : LineDiffRow(text)
    class Equal(text: String) : LineDiffRow(text)
}
