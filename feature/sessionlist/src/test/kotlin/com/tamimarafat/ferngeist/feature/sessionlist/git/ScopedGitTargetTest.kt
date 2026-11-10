package com.tamimarafat.ferngeist.feature.sessionlist.git

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopedGitTargetTest {
    private val gateway = GatewaySource(id = "one", name = "One", host = "one.test", gatewayCredential = "paired")

    @Test
    fun mainAndWorktreeUseTheirOwnCheckout() {
        assertEquals("/repo", scopedGitTarget(gateway, "/repo", WorktreeScope.Main)?.cwd)
        assertEquals("/trees/fix", scopedGitTarget(gateway, "/repo", WorktreeScope.Worktree("/trees/fix"))?.cwd)
    }

    @Test
    fun allDefaultsToMainCheckout() {
        assertEquals("/repo", scopedGitTarget(gateway, "/repo", WorktreeScope.All)?.cwd)
    }

    @Test
    fun missingProjectHasNoTarget() {
        assertNull(scopedGitTarget(gateway, "", WorktreeScope.Main))
        assertNull(scopedGitTarget(null, "/repo", WorktreeScope.Main))
    }

    @Test
    fun identicalPathsOnDifferentGatewaysRemainDistinctTargets() {
        val other = gateway.copy(id = "two", host = "two.test", gatewayCredential = "other-pairing")
        val first = scopedGitTarget(gateway, "/repo", WorktreeScope.Main)
        val second = scopedGitTarget(other, "/repo", WorktreeScope.Main)
        assertNotEquals(first, second)
        assertEquals("one.test", first?.host)
        assertEquals("two.test", second?.host)
    }
}
