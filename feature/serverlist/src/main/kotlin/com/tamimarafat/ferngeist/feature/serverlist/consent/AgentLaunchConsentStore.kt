package com.tamimarafat.ferngeist.feature.serverlist.consent

import kotlinx.coroutines.flow.Flow

interface AgentLaunchConsentStore {
    val anyConsent: Flow<Boolean>

    suspend fun hasConsent(key: String): Boolean

    suspend fun setConsent(
        key: String,
        granted: Boolean,
    )

    suspend fun clearByPrefix(prefix: String)
}
