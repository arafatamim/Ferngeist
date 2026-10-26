package com.tamimarafat.ferngeist.feature.serverlist

import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import javax.inject.Inject

/**
 * The repositories the server list reads and writes. Injected as one value so the server and
 * gateway view models share a single set of stores without each taking every one.
 */
data class ServerRepositories
    @Inject
    constructor(
        val launchableTargetRepository: LaunchableTargetRepository,
        val gatewaySourceRepository: GatewaySourceRepository,
        val sessionRepository: ChatCatalog,
        val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
    )
