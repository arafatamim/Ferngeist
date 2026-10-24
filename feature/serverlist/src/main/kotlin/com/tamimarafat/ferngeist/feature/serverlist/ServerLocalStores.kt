package com.tamimarafat.ferngeist.feature.serverlist

import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.core.model.store.RecentCwdStore
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.serverlist.consent.AgentLaunchConsentStore
import javax.inject.Inject

/**
 * The per-server local stores that deleting a server clears: env values, launch consent and
 * the recent-selection and working-directory history.
 */
data class ServerLocalStores
    @Inject
    constructor(
        val authEnvValueStore: AuthEnvValueStore,
        val agentLaunchConsentStore: AgentLaunchConsentStore,
        val recentCwdStore: RecentCwdStore,
        val recentSelectionStore: RecentSelectionStore,
    )
