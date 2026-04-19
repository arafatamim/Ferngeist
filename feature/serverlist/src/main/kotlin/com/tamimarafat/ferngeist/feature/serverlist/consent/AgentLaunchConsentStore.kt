package com.tamimarafat.ferngeist.feature.serverlist.consent

interface AgentLaunchConsentStore {
    suspend fun hasConsent(key: String): Boolean

    suspend fun setConsent(key: String, granted: Boolean)

    suspend fun clearByPrefix(prefix: String)
}
