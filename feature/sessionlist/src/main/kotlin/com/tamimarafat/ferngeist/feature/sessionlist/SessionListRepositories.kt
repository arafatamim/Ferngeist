package com.tamimarafat.ferngeist.feature.sessionlist

import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.RecentCwdStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import javax.inject.Inject

/**
 * The repositories one session list reads and writes. Injected as one value so the view model
 * and its collaborators share a single set of stores without each taking the full list.
 */
data class SessionListRepositories
    @Inject
    constructor(
        val launchableTargetRepository: LaunchableTargetRepository,
        val sessionRepository: ChatCatalog,
        val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
        val recentCwdStore: RecentCwdStore,
        val gatewayRepository: GatewayRepository,
        val gatewaySourceRepository: GatewaySourceRepository,
    )
