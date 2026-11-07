package com.tamimarafat.ferngeist.feature.serverlist.consent

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class GrantedConsentKeysTest {
    @Test
    fun `parses only granted consent keys`() {
        val prefs: Map<Preferences.Key<*>, Any> =
            mapOf(
                Pair(booleanPreferencesKey("consent.gw:claude"), true),
                Pair(booleanPreferencesKey("consent.gw:codex"), false),
                Pair(booleanPreferencesKey("unrelated"), true),
            )

        assertEquals(setOf("gw:claude"), grantedConsentKeys(prefs))
    }
}
