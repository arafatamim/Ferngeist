package com.tamimarafat.ferngeist.feature.serverlist.consent

import kotlinx.coroutines.flow.Flow

interface AgentLaunchConsentStore {
    /** Launch-consent keys currently granted on this phone. */
    val consentedKeys: Flow<Set<String>>

    suspend fun hasConsent(key: String): Boolean

    suspend fun setConsent(
        key: String,
        granted: Boolean,
    )

    suspend fun clearByPrefix(prefix: String)
}
