package com.tamimarafat.ferngeist.feature.chat.ui

import com.tamimarafat.ferngeist.feature.chat.ui.history.commitWebUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommitWebUrlTest {
    private val sha = "4f9c2e1a"

    @Test
    fun everyRemoteForm_mapsToTheSameCommitPage() {
        val expected = "https://github.com/arafatamim/Ferngeist/commit/$sha"
        listOf(
            "git@github.com:arafatamim/Ferngeist.git",
            "git@github.com:arafatamim/Ferngeist",
            "https://github.com/arafatamim/Ferngeist.git",
            "https://github.com/arafatamim/Ferngeist/",
            "ssh://git@github.com:22/arafatamim/Ferngeist.git",
            "git://github.com/arafatamim/Ferngeist.git",
        ).forEach { assertEquals(it, expected, commitWebUrl(it, sha)) }
    }

    @Test
    fun credentialsInTheRemote_neverReachTheLink() {
        assertEquals(
            "https://github.com/o/r/commit/$sha",
            commitWebUrl("https://x-access-token:ghp_secret@github.com/o/r.git", sha),
        )
    }

    @Test
    fun gitLabSubgroups_keepEveryPathSegment() {
        assertEquals(
            "https://gitlab.com/group/sub/repo/commit/$sha",
            commitWebUrl("git@gitlab.com:group/sub/repo.git", sha),
        )
    }

    @Test
    fun localOrMalformedRemotes_andBadShas_giveNoLink() {
        assertNull(commitWebUrl("/srv/git/repo.git", sha))
        assertNull(commitWebUrl("file:///srv/git/repo.git", sha))
        assertNull(commitWebUrl("https://github.com/onlyowner", sha))
        assertNull(commitWebUrl("https://github.com/o/../r", sha))
        assertNull(commitWebUrl("git@github.com:o/r.git", "HEAD"))
    }
}
