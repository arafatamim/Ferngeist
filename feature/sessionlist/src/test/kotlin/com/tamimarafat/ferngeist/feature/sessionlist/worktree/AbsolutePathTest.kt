package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsolutePathTest {
    @Test
    fun isAbsolutePath_acceptsUnixAndDrivePathsOnly() {
        assertTrue(isAbsolutePath("/home/me/repo/.worktrees/x"))
        assertTrue(isAbsolutePath("C:\\Users\\me\\repo"))
        assertTrue(isAbsolutePath("d:/work"))
        assertFalse(isAbsolutePath("repo/sub"))
        assertFalse(isAbsolutePath("C:repo"))
        assertFalse(isAbsolutePath(""))
    }
}
