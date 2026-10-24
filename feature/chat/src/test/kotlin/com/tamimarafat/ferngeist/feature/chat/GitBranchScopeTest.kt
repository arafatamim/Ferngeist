package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitBranchScopeTest {
    private fun commit(sha: String) =
        ChatGitCommit(
            sha = sha,
            parents = emptyList(),
            subject = sha,
            authorName = "A",
            authorEmail = "a@x",
            authorTimeMillis = 0,
        )

    @Test
    fun `the base is where the branch's own commits stop, and it is loaded`() {
        val commits = listOf("mine2", "mine1", "base", "older1", "older2").map(::commit)

        val scope = gitBranchScope(commits, base = "base", hasMore = false)

        assertEquals(2, scope.ownCount)
        assertTrue(scope.baseLoaded)
    }

    @Test
    fun `a base beyond the loaded pages leaves every loaded row the branch's`() {
        val commits = listOf("mine2", "mine1").map(::commit)

        val scope = gitBranchScope(commits, base = "base", hasMore = true)

        assertEquals(2, scope.ownCount)
        assertFalse(scope.baseLoaded)
    }

    @Test
    fun `a base that is not on the walk marks nothing once every page is in`() {
        // A rebase moved the branch off the walk the gateway handed back, so no row is its own.
        val commits = listOf("mine", "older").map(::commit)

        assertEquals(GitBranchScope.none, gitBranchScope(commits, base = "gone", hasMore = false))
    }

    @Test
    fun `no base marks nothing`() {
        val commits = listOf("a", "b").map(::commit)

        assertEquals(GitBranchScope.none, gitBranchScope(commits, base = null, hasMore = true))
    }

    @Test
    fun `a branch with no commits of its own has the base at the top`() {
        val commits = listOf("base", "older").map(::commit)

        val scope = gitBranchScope(commits, base = "base", hasMore = false)

        assertEquals(0, scope.ownCount)
        assertTrue(scope.baseLoaded)
    }

    @Test
    fun `an empty repository has nothing to mark`() {
        assertEquals(GitBranchScope.none, gitBranchScope(emptyList(), base = "base", hasMore = false))
    }

    @Test
    fun `the branch point fades over the two rows around it instead of stepping`() {
        // Two of the branch's own rows, then the base and the history under it.
        val fades = bandFades(listOf(0f, 0f, 1f, 1f))

        assertEquals(BandFade(top = 0f, bottom = 0f), fades[0])
        assertEquals(BandFade(top = 0f, bottom = 0.5f), fades[1])
        assertEquals(BandFade(top = 0.5f, bottom = 1f), fades[2])
        assertEquals(BandFade(top = 1f, bottom = 1f), fades[3])
    }

    @Test
    fun `neighbouring bands agree on the colour at the line between them`() {
        // A day header repeats the mark of the row it sits above, so runs of equal marks are common;
        // whatever the marks are, no junction may jump.
        val marks = listOf(0f, 1f, 0f, 1f, 1f, 0f)
        val fades = bandFades(marks)

        fades.zipWithNext { above, below -> assertEquals(above.bottom, below.top) }
    }

    @Test
    fun `a history with nothing marked draws one colour all the way down`() {
        assertEquals(List(3) { BandFade.none }, bandFades(listOf(0f, 0f, 0f)))
        assertEquals(List(2) { BandFade(top = 1f, bottom = 1f) }, bandFades(listOf(1f, 1f)))
    }
}
