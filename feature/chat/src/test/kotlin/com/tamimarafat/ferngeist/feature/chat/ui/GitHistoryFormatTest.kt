package com.tamimarafat.ferngeist.feature.chat.ui

import android.text.format.DateUtils
import com.tamimarafat.ferngeist.core.common.ui.time.RelativeAge
import com.tamimarafat.ferngeist.core.common.ui.time.relativeAge
import com.tamimarafat.ferngeist.feature.chat.ui.history.avatarUrl
import com.tamimarafat.ferngeist.feature.chat.ui.history.initialsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHistoryFormatTest {
    private val now = 1_792_000_000_000L

    @Test
    fun relativeAge_bucketsByElapsedTime() {
        assertEquals(RelativeAge.Now, relativeAge(now, now - 59_000))
        assertEquals(RelativeAge.Minutes(12), relativeAge(now, now - 12 * DateUtils.MINUTE_IN_MILLIS))
        assertEquals(RelativeAge.Minutes(59), relativeAge(now, now - DateUtils.HOUR_IN_MILLIS + 1))
        assertEquals(RelativeAge.Hours(3), relativeAge(now, now - 3 * DateUtils.HOUR_IN_MILLIS - 1))
        assertEquals(RelativeAge.Days(1), relativeAge(now, now - DateUtils.DAY_IN_MILLIS))
        assertEquals(RelativeAge.Days(6), relativeAge(now, now - 6 * DateUtils.DAY_IN_MILLIS))
        assertEquals(RelativeAge.Older, relativeAge(now, now - DateUtils.WEEK_IN_MILLIS))
    }

    @Test
    fun relativeAge_futureTimestampReadsAsNow() {
        assertEquals(RelativeAge.Now, relativeAge(now, now + DateUtils.HOUR_IN_MILLIS))
    }

    @Test
    fun initialsOf_usesFirstAndLastWordThenFallsBack() {
        assertEquals("TA", initialsOf("Tamim  Arafat", "t@x"))
        assertEquals("AL", initialsOf("ada byron lovelace", "a@x"))
        assertEquals("C", initialsOf("Claude", "c@x"))
        assertEquals("X", initialsOf(" ", "x@y"))
        assertEquals("?", initialsOf("", ""))
    }

    @Test
    fun avatarUrl_hashesTheNormalizedEmailForGravatar() {
        assertEquals(
            "https://gravatar.com/avatar/eb2b6c0d061bbd5caa545b6d1184a1887b11dba0b1d7fd8ca5b42ebf0ad7d3a8?s=72&d=404",
            avatarUrl(" Dev@Example.com ", 72),
        )
    }

    @Test
    fun avatarUrl_usesGitHubsAvatarForNoreplyAddressesAndNothingForNoEmail() {
        assertEquals(
            "https://avatars.githubusercontent.com/u/123?s=72",
            avatarUrl("123+octo@users.noreply.github.com", 72),
        )
        assertNull(avatarUrl(" ", 72))
    }
}
