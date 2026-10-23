package com.tamimarafat.ferngeist.di

import com.tamimarafat.ferngeist.acp.bridge.facade.HubChatPresence
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.ChatSessionPresence
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the chat feature's hub-facing presence port to its hub-backed adapter.
 *
 * Kept out of [AppModule] so the ACP bridge stays the only module that knows both sides.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatPresenceModule {
    /** The process-wide hub presence the chat screen reads and writes. */
    @Provides
    @Singleton
    fun provideChatSessionPresence(
        hub: ChatConnectionHub,
        launchableTargetRepository: LaunchableTargetRepository,
        gatewayRepository: GatewayRepository,
    ): ChatSessionPresence =
        HubChatPresence(
            hub = hub,
            launchableTargetRepository = launchableTargetRepository,
            gatewayRepository = gatewayRepository,
        )
}
