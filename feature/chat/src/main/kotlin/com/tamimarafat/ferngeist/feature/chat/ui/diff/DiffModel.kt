package com.tamimarafat.ferngeist.feature.chat.ui.diff

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.feature.chat.ui.DIFF_BLOCK_COUNT
import com.tamimarafat.ferngeist.feature.chat.ui.DIFF_CONTEXT_LINES
import com.tamimarafat.ferngeist.feature.chat.ui.DIFF_EQUAL_BLOCK_COUNT
import com.tamimarafat.ferngeist.feature.chat.ui.LineDiffRow
import com.tamimarafat.ferngeist.feature.chat.ui.MAX_DIFF_ROWS
import io.github.diff.DeltaType
import io.github.diff.generatePatch

internal fun isDirectoryPath(path: String): Boolean = path.endsWith('/')

/**
 * True when a git status entry is a directory (submodule or collapsed
 * untracked dir) and therefore not diffable. [ChatChangedFile.isDir] is
 * authoritative (submodule paths carry no trailing slash); the trailing-slash
 * check is a fallback for older gateways that report collapsed dirs only via
 * the path.
 */
internal fun isDirectoryEntry(file: ChatChangedFile): Boolean = file.isDir || isDirectoryPath(file.path)

/** Returns the last path segment of a file path, or the full path when it ends in '/'. */
internal fun fileNameOf(path: String): String =
    path
        .substringAfterLast('/', missingDelimiterValue = path)
        .ifEmpty { path }

/**
 * Produces the complete list of [LineDiffRow] entries from a patch diff between
 * [oldLines] and [newLines], including equal lines surrounding changes.
 */
internal fun buildFullDiffRows(
    oldLines: List<String>,
    newLines: List<String>,
): List<LineDiffRow> {
    val fullRows = mutableListOf<LineDiffRow>()

    if (oldLines.isEmpty()) {
        newLines.forEach { line -> fullRows.add(LineDiffRow.Insert(line)) }
        return fullRows
    }

    val patch =
        generatePatch {
            original = oldLines
            revised = newLines
        }
    var oldPos = 0
    for (delta in patch.getDeltas()) {
        val sourceChunk = delta.source
        val targetChunk = delta.target
        val equalCount = sourceChunk.position - oldPos
        for (i in 0 until equalCount) {
            fullRows.add(LineDiffRow.Equal(oldLines[oldPos + i]))
        }
        oldPos = sourceChunk.position
        when (delta.type) {
            DeltaType.DELETE -> {
                sourceChunk.lines.forEach { fullRows.add(LineDiffRow.Delete(it)) }
                oldPos += sourceChunk.lines.size
            }
            DeltaType.INSERT -> {
                targetChunk.lines.forEach { fullRows.add(LineDiffRow.Insert(it)) }
            }
            DeltaType.CHANGE -> {
                sourceChunk.lines.forEach { fullRows.add(LineDiffRow.Delete(it)) }
                targetChunk.lines.forEach { fullRows.add(LineDiffRow.Insert(it)) }
                oldPos += sourceChunk.lines.size
            }
            // Equal runs are emitted positionally before each non-equal delta.
            DeltaType.EQUAL -> Unit
        }
    }

    for (i in oldPos until oldLines.size) {
        fullRows.add(LineDiffRow.Equal(oldLines[i]))
    }
    return fullRows
}

/**
 * Merges overlapping or adjacent context windows around [changedIndexes] into
 * inclusive [IntRange] values, each extended by [DIFF_CONTEXT_LINES] on both sides.
 */
internal fun mergeContextRanges(
    changedIndexes: List<Int>,
    lastIndex: Int,
): List<IntRange> =
    changedIndexes
        .map { index ->
            (index - DIFF_CONTEXT_LINES).coerceAtLeast(0)..(index + DIFF_CONTEXT_LINES).coerceAtMost(lastIndex)
        }.fold(mutableListOf<IntRange>()) { merged, range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else {
                merged.add(range)
            }
            merged
        }

/**
 * Applies context-window merging to [fullRows], collapsing unchanged regions
 * between changed hunks to [LineDiffRow.Omitted] markers and capping the total
 * at [MAX_DIFF_ROWS] lines.
 */
internal fun collapseDiffRows(fullRows: List<LineDiffRow>): List<LineDiffRow> {
    val changedIndexes = fullRows.indices.filter { fullRows[it].isChanged }
    if (changedIndexes.isEmpty()) return emptyList()

    val ranges = mergeContextRanges(changedIndexes, fullRows.lastIndex)

    val visibleRows = mutableListOf<LineDiffRow>()
    ranges.forEachIndexed { index, range ->
        if (index > 0) visibleRows.add(LineDiffRow.Omitted("…"))
        visibleRows.addAll(fullRows.subList(range.first, range.last + 1))
    }
    return if (visibleRows.size > MAX_DIFF_ROWS) {
        visibleRows.take(MAX_DIFF_ROWS).toMutableList().apply {
            add(LineDiffRow.Omitted("… ${visibleRows.size - MAX_DIFF_ROWS} more lines"))
        }
    } else {
        visibleRows
    }
}

/**
 * Builds the visible portion of a unified diff: changed rows, five equal rows
 * of context around each changed region, and omission rows between distant
 * regions. An unchanged file has no diff rows to display.
 */
internal fun buildDiffRows(
    oldText: String?,
    newText: String,
): List<LineDiffRow> {
    val oldLines = oldText?.diffLines().orEmpty()
    val newLines = newText.diffLines()
    val fullRows = buildFullDiffRows(oldLines, newLines)
    return collapseDiffRows(fullRows)
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
internal fun computeDiffBlocks(
    additions: Int,
    deletions: Int,
): DiffBlockResult {
    val total = additions + deletions
    if (total == 0) return DiffBlockResult(0, 0, DIFF_BLOCK_COUNT)

    // Balanced diff: show an exact 2/2 split (4 bars) instead of the 5-bar
    // rounding asymmetry (round(2.5)=3 green, 2 red). Only for strictly equal
    // non-zero counts; anything else keeps the 5-bar proportion.
    if (additions == deletions) {
        return DiffBlockResult(DIFF_EQUAL_BLOCK_COUNT, DIFF_EQUAL_BLOCK_COUNT, 0)
    }

    // Fixed block count: small enough to fit in a row, enough blocks to show proportion.
    val totalBlocks = DIFF_BLOCK_COUNT

    var green = kotlin.math.round(additions.toFloat() / total * totalBlocks).toInt()
    var red = totalBlocks - green

    // When a non-zero count would round to zero blocks, force at least one so the visual isn't misleading.
    if (additions > 0 && green == 0) {
        green = 1
        red -= 1
    }
    if (deletions > 0 && red == 0) {
        red = 1
        green -= 1
    }

    return DiffBlockResult(green, red, DIFF_BLOCK_COUNT - green - red)
}

internal data class DiffStats(
    val additions: Int,
    val deletions: Int,
)

// A final newline ends the last line rather than starting an empty one, as git counts it.
// An empty text has no lines (a new or deleted file), and a final newline does not start one.

internal fun String.diffLines(): List<String> {
    val lines = lines()
    return if (lines.last().isEmpty()) lines.dropLast(1) else lines
}

internal fun List<ToolCallContent.Diff>.computeDiffStats(): DiffStats =
    fold(DiffStats(0, 0)) { total, diff ->
        val stats = diff.lineStats()
        DiffStats(total.additions + stats.additions, total.deletions + stats.deletions)
    }

/** One diff's line counts; a new file (no old text) is all additions. */
internal fun ToolCallContent.Diff.lineStats(): DiffStats {
    val oldLines = oldText?.diffLines().orEmpty()
    val newLines = newText.diffLines()
    if (oldLines.isEmpty()) return DiffStats(newLines.size, 0)
    val patch =
        generatePatch {
            original = oldLines
            revised = newLines
        }
    return patch.getDeltas().fold(DiffStats(0, 0)) { total, delta ->
        when (delta.type) {
            DeltaType.INSERT -> DiffStats(total.additions + delta.target.lines.size, total.deletions)
            DeltaType.DELETE -> DiffStats(total.additions, total.deletions + delta.source.lines.size)
            DeltaType.CHANGE ->
                DiffStats(
                    total.additions + delta.target.lines.size,
                    total.deletions + delta.source.lines.size,
                )
            DeltaType.EQUAL -> total
        }
    }
}
