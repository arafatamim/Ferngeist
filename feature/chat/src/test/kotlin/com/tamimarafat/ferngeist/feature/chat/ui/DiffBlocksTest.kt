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
        val diff = ToolCallContent.Diff(
            oldText = "a\nb\nc",
            newText = "a\nx\ny\nc",
            path = "file.txt",
        )
        // old: [a,b,c], new: [a,x,y,c] -> patch inserts x,y = 2 additions
        assertEquals(2, diff.computeAdditions())
    }

    @Test
    fun diffHelper_deletions_countsDeletedLines() {
        val diff = ToolCallContent.Diff(
            oldText = "a\nb\nc\nd",
            newText = "a\nd",
            path = "file.txt",
        )
        assertEquals(2, diff.computeDeletions())
    }

    @Test
    fun diffHelper_mixedCountsBoth() {
        val diff = ToolCallContent.Diff(
            oldText = "a\nb\nc",
            newText = "a\nx\nc",
            path = "file.txt",
        )
        assertEquals(1, diff.computeAdditions())
        assertEquals(1, diff.computeDeletions())
    }

    @Test
    fun diffHelper_noOldText_treatsAllAsAdditions() {
        val diff = ToolCallContent.Diff(
            oldText = null,
            newText = "x\ny\nz",
            path = "file.txt",
        )
        assertEquals(3, diff.computeAdditions())
        assertEquals(0, diff.computeDeletions())
    }
}
