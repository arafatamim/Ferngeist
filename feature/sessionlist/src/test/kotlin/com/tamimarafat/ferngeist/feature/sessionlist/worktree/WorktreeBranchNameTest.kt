package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WorktreeBranchNameTest {
    @Test
    fun newWorktreeBranchName_isThreeLowercaseWordsUnderFerngeist() {
        repeat(100) { seed ->
            assertTrue(Regex("ferngeist/[a-z]+-[a-z]+-[a-z]+").matches(newWorktreeBranchName(Random(seed))))
        }
    }

    @Test
    fun newWorktreeBranchName_variesWithTheRandomSource() {
        assertEquals(newWorktreeBranchName(Random(1)), newWorktreeBranchName(Random(1)))
        assertNotEquals(newWorktreeBranchName(Random(1)), newWorktreeBranchName(Random(2)))
    }
}
