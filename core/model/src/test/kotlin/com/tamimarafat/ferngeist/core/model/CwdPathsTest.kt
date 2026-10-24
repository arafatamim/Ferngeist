package com.tamimarafat.ferngeist.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CwdPathsTest {
    @Test
    fun isSameCwd_ignoresSeparatorsCaseAndTrailingSlash() {
        assertEquals(true, isSameCwd("C:\\work\\api\\.worktrees\\feat-x", "c:/work/api/.worktrees/feat-x/"))
        assertEquals(false, isSameCwd("/work/api/.worktrees/feat-x", "/work/api"))
    }

    @Test
    fun pathSegments_splitsOnEitherSeparatorAndDropsEmptyParts() {
        assertEquals(listOf("home", "tamim", "work"), pathSegments("/home/tamim//work/"))
        assertEquals(listOf("C:", "work", "api"), pathSegments("C:\\work\\api"))
        assertEquals(emptyList<String>(), pathSegments("  /  "))
    }

    @Test
    fun projectName_isTheLastFolder() {
        assertEquals("Ferngeist", projectName("/home/tamim/src/Ferngeist"))
        assertEquals("Ferngeist", projectName("C:\\Users\\tamim\\src\\Ferngeist\\"))
        assertEquals("Ferngeist", projectName("Ferngeist"))
    }

    @Test
    fun worktreeRepoOf_readsOnlyTheFolderSegment() {
        assertEquals("/work/api", worktreeRepoOf("/work/api/.worktrees/feat-x"))
        // A repo that merely has "worktrees" in its name is not a managed worktree.
        assertEquals(null, worktreeRepoOf("/work/apix.worktrees/feat-x"))
        assertEquals(null, worktreeRepoOf("/work/api"))
        assertEquals(null, worktreeRepoOf(".worktrees/feat-x"))
        assertEquals("C:\\work\\api", worktreeRepoOf("C:\\work\\api\\.worktrees\\feat-x"))
    }

    @Test
    fun worktreeBranchOf_namesTheFolderAfterTheWorktreesSegment() {
        assertEquals("feat-x", worktreeBranchOf("/work/api/.worktrees/feat-x"))
        assertEquals("feat-x", worktreeBranchOf("C:\\work\\api\\.worktrees\\feat-x\\"))
        assertEquals(null, worktreeBranchOf("/work/api"))
        assertEquals(null, worktreeBranchOf(".worktrees/feat-x"))
    }

    @Test
    fun worktreeOf_usesTheListedRepoBecauseTheFolderNamesTheBranch() {
        val listed =
            WorktreeRef(
                repo = "/orca/workspaces/Ferngeist",
                path = "/orca/workspaces/Ferngeist/feat-x",
                branch = "feat-x",
            )

        val worktree = worktreeOf("/orca/workspaces/Ferngeist/feat-x", listOf(listed))

        assertEquals("/orca/workspaces/Ferngeist", worktree?.repo)
        assertEquals("feat-x", worktree?.branch)
        assertEquals("Ferngeist", projectName(projectRootOf(listed.path, listOf(listed))))
    }

    @Test
    fun worktreeOf_fallsBackToTheWorktreesFolderConvention() {
        val worktree = worktreeOf("/work/api/.worktrees/feat-y", emptyList())

        assertEquals("/work/api", worktree?.repo)
        assertEquals("feat-y", worktree?.branch)
        assertEquals("/work/api", projectRootOf("/work/api", emptyList()))
    }
}
