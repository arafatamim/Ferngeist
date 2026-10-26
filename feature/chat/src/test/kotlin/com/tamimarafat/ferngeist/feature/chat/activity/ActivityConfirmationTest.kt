package com.tamimarafat.ferngeist.feature.chat.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityConfirmationTest {
    private val now = 1_000_000L

    @Test
    fun recentListedActivityNewerThanPhoneTurn_needsConfirmation() {
        assertTrue(ActivityConfirmation(now - 1).needsConfirmation(now - 2, now))
        assertTrue(ActivityConfirmation(now - 119_999).needsConfirmation(null, now))
    }

    @Test
    fun oldUnknownFutureOrPhoneOwnedActivity_doesNotNeedConfirmation() {
        assertFalse(ActivityConfirmation(now - 120_000).needsConfirmation(null, now))
        assertFalse(ActivityConfirmation(null).needsConfirmation(null, now))
        assertFalse(ActivityConfirmation(-1).needsConfirmation(null, now))
        assertFalse(ActivityConfirmation(now + 1).needsConfirmation(null, now))
        assertFalse(ActivityConfirmation(now - 1).needsConfirmation(now - 1, now))
        assertFalse(ActivityConfirmation(now - 1).needsConfirmation(now, now))
    }

    @Test
    fun dismissedConfirmation_isNotRepeatedUntilAnotherOpening() {
        val opening = ActivityConfirmation(now - 1)
        assertTrue(opening.needsConfirmation(null, now))
        assertFalse(opening.needsConfirmation(null, now))
        assertTrue(ActivityConfirmation(now - 1).needsConfirmation(null, now))
    }
}
