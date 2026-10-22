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
    fun cwdKey_matchesTheGatewayPathDespiteSpelling() {
        assertEquals(cwdKey("C:/work/api/.worktrees/feat-x"), cwdKey("c:\\Work\\api\\.worktrees\\feat-x\\"))
    }

    @Test
    fun pathSegments_splitsOnEitherSeparatorAndDropsEmptyParts() {
        assertEquals(listOf("home", "tamim", "work"), pathSegments("/home/tamim//work/"))
        assertEquals(listOf("C:", "work", "api"), pathSegments("C:\\work\\api"))
        assertEquals(emptyList<String>(), pathSegments("  /  "))
    }
}
