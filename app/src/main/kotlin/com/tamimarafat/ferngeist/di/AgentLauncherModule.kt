package com.tamimarafat.ferngeist.di

import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog
import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLauncher
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/**
 * Wires the server list's [AgentLauncher] to the shared hub and gateway clients.
 *
 * Kept out of [AppModule] so the server list's ACP seam is wired in one place.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentLauncherModule {
    /** The launcher that owns every verification transport the server list opens. */
    @Provides
    @Singleton
    fun provideAgentLauncher(
        hub: ChatConnectionHub,
        repositories: LaunchRepositories,
        authEnvValueStore: AuthEnvValueStore,
        sessionCatalog: SessionCatalog,
    ): AgentLauncher =
        AgentLauncher(
            hub = hub,
            repositories = repositories,
            authEnvValueStore = authEnvValueStore,
            sessionCatalog = sessionCatalog,
        )

    @Provides
    fun provideLaunchRepositories(
        gatewayRepository: GatewayRepository,
        gatewaySourceRepository: GatewaySourceRepository,
        launchableTargetRepository: LaunchableTargetRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): LaunchRepositories =
        LaunchRepositories(
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            launchableTargetRepository = launchableTargetRepository,
            ioDispatcher = ioDispatcher,
        )
}
