package com.tamimarafat.ferngeist.feature.chat.ui

import com.agentclientprotocol.model.ToolCallContent
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffBlocksTest {
    @Test
    fun zeroChanges_shouldBeFiveEmpty() {
        val result = computeDiffBlocks(0, 0)
        assertEquals(DiffBlockResult(0, 0, 5), result)
    }

    @Test
    fun onlyAdditions_setsAllGreen() {
        val result = computeDiffBlocks(5, 0)
        assertEquals(DiffBlockResult(5, 0, 0), result)
    }

    @Test
    fun onlyDeletions_setsAllRed() {
        val result = computeDiffBlocks(0, 5)
        assertEquals(DiffBlockResult(0, 5, 0), result)
    }

    @Test
    fun mixedRatio() {
        // A=10, D=2 -> round(10/12*5)=4 green, 1 red
        val result = computeDiffBlocks(10, 2)
        assertEquals(DiffBlockResult(4, 1, 0), result)
    }

    @Test
    fun balanced_usesExactTwoTwoSplit() {
        // A == D (non-zero) -> exact 2/2 (4 bars), not the 5-bar 3/2 rounding artifact.
        assertEquals(DiffBlockResult(2, 2, 0), computeDiffBlocks(3, 3))
        assertEquals(DiffBlockResult(2, 2, 0), computeDiffBlocks(1, 1))
    }

    @Test
    fun visibilityGuard_whenAdditionRoundingToZero_forcesOneGreen() {
        // A=1, D=100 -> round(1/101*5)=0 -> forced 1 green, 4 red
        val result = computeDiffBlocks(1, 100)
        assertEquals(DiffBlockResult(1, 4, 0), result)
    }

    @Test
    fun visibilityGuard_whenDeletionRoundingToZero_forcesOneRed() {
        // A=100, D=1 -> round(100/101*5)=5 green -> 0 red -> forced 4 green, 1 red
        val result = computeDiffBlocks(100, 1)
        assertEquals(DiffBlockResult(4, 1, 0), result)
    }

    @Test
    fun diffHelper_additions_countsInsertedLines() {
        val diff =
            ToolCallContent.Diff(
                oldText = "a\nb\nc",
                newText = "a\nx\ny\nc",
                path = "file.txt",
            )
        // old: [a,b,c], new: [a,x,y,c] -> patch inserts x,y = 2 additions
        assertEquals(2, diff.computeAdditions())
    }

    @Test
    fun diffHelper_deletions_countsDeletedLines() {
        val diff =
            ToolCallContent.Diff(
                oldText = "a\nb\nc\nd",
                newText = "a\nd",
                path = "file.txt",
            )
        assertEquals(2, diff.computeDeletions())
    }

    @Test
    fun diffHelper_mixedCountsBoth() {
        val diff =
            ToolCallContent.Diff(
                oldText = "a\nb\nc",
                newText = "a\nx\nc",
                path = "file.txt",
            )
        assertEquals(1, diff.computeAdditions())
        assertEquals(1, diff.computeDeletions())
    }

    @Test
    fun diffHelper_noOldText_treatsAllAsAdditions() {
        val diff =
            ToolCallContent.Diff(
                oldText = null,
                newText = "x\ny\nz",
                path = "file.txt",
            )
        assertEquals(3, diff.computeAdditions())
        assertEquals(0, diff.computeDeletions())
    }

    @Test
    fun diffRows_distantChanges_includeFiveContextLinesAndOmission() {
        val oldText = (0 until 30).joinToString("\n") { "line-$it" }
        val newText =
            (0 until 30).joinToString("\n") {
                when (it) {
                    2 -> "changed-2"
                    25 -> "changed-25"
                    else -> "line-$it"
                }
            }

        val rows = buildDiffRows(oldText, newText)

        assertEquals("line-0", rows[0].text)
        assertEquals("line-1", rows[1].text)
        assertEquals("line-2", (rows[2] as LineDiffRow.Delete).text)
        assertEquals("line-7", rows[8].text)
        assertEquals("…", (rows[9] as LineDiffRow.Omitted).text)
        assertEquals("line-20", rows[10].text)
        assertEquals("line-24", rows[14].text)
        assertEquals("line-25", (rows[15] as LineDiffRow.Delete).text)
        assertEquals("changed-25", (rows[16] as LineDiffRow.Insert).text)
        assertEquals("line-29", rows.last().text)
    }

    @Test
    fun diffRows_nearbyChanges_mergeOverlappingContextWindows() {
        val oldText = (0 until 30).joinToString("\n") { "line-$it" }
        val newText =
            (0 until 30).joinToString("\n") {
                when (it) {
                    10 -> "changed-10"
                    15 -> "changed-15"
                    else -> "line-$it"
                }
            }

        val rows = buildDiffRows(oldText, newText)

        assert(rows.none { it is LineDiffRow.Omitted })
        assertEquals("line-5", rows.first().text)
        assertEquals("line-20", rows.last().text)
    }

    @Test
    fun diffRows_newFile_keepsAllInsertedLines() {
        val rows = buildDiffRows(null, (0 until 20).joinToString("\n") { "line-$it" })

        assertEquals(20, rows.size)
        assert(rows.all { it is LineDiffRow.Insert })
    }

    @Test
    fun diffRows_unchangedFile_returnsNoRows() {
        assertEquals(emptyList<LineDiffRow>(), buildDiffRows("same", "same"))
    }

    @Test
    fun directoryPath_isNotDiffable() {
        assert(isDirectoryPath(".commandcode/"))
        assert(isDirectoryPath("demo-screenshots/"))
        assert(!isDirectoryPath("feature/chat/ChatViewModel.kt"))
    }

    @Test
    fun fileNameOf_returnsLastPathSegment() {
        assertEquals("ChatViewModel.kt", fileNameOf("feature/chat/ChatViewModel.kt"))
        assertEquals("c.txt", fileNameOf("a/b/c.txt"))
        assertEquals("plain.kt", fileNameOf("plain.kt"))
    }

    @Test
    fun fileNameOf_trailingSlashFallsBackToFullPath() {
        assertEquals("demo-screenshots/", fileNameOf("demo-screenshots/"))
    }
}
