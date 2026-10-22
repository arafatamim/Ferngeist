package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import com.tamimarafat.ferngeist.gateway.GatewayGitRef
import org.junit.Assert.assertEquals
import org.junit.Test

class BaseSuggestionsTest {
    private val refs =
        listOf(
            GatewayGitRef("v1.2", "tag"),
            GatewayGitRef("origin/release/1.2", "remote"),
            GatewayGitRef("release/1.2", "branch"),
            GatewayGitRef("main", "branch"),
            GatewayGitRef("HEAD", "head"),
        )

    @Test
    fun baseSuggestions_matchesAnyCaseAndOrdersBranchesBeforeRemotes() {
        assertEquals(listOf("release/1.2", "origin/release/1.2"), baseSuggestions(refs, "REL", emptyList()))
    }

    @Test
    fun baseSuggestions_dropsChipsAndDetachedHead() {
        assertEquals(
            listOf("release/1.2", "origin/release/1.2", "v1.2"),
            baseSuggestions(refs, "", offered = listOf("main")),
        )
    }
}
