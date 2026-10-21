package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeBranchNameTest {
    @Test
    fun newWorktreeBranchName_matchesTheGatewayDefaultShape() {
        assertTrue(Regex("ferngeist/[0-9a-f]{8}").matches(newWorktreeBranchName()))
    }

    @Test
    fun newWorktreeBranchName_differsBetweenCalls() {
        assertNotEquals(newWorktreeBranchName(), newWorktreeBranchName())
    }
}
