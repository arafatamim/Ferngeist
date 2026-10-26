package com.tamimarafat.ferngeist.di

import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.core.model.repository.TurnTimingRepository
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import com.tamimarafat.ferngeist.data.database.crypto.CredentialEncryptor
import com.tamimarafat.ferngeist.data.database.repository.GatewayAgentBindingRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.GatewaySourceRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.LaunchableTargetRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.LaunchableTargetSessionSettingsRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.ServerRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.TurnTimingRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the Room-backed repositories the app and its registries read through. */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    /** Binds the server repository backed by Room. */
    @Provides
    @Singleton
    fun provideServerRepository(
        database: FerngeistDatabase,
        credentialEncryptor: CredentialEncryptor,
    ): ServerRepository = ServerRepositoryImpl(database.serverDao(), credentialEncryptor)

    /** Binds the gateway source repository backed by Room. */
    @Provides
    @Singleton
    fun provideGatewaySourceRepository(
        database: FerngeistDatabase,
        credentialEncryptor: CredentialEncryptor,
    ): GatewaySourceRepository =
        GatewaySourceRepositoryImpl(
            database.gatewaySourceDao(),
            credentialEncryptor,
        )

    /** Binds gateway agent bindings stored in the database. */
    @Provides
    @Singleton
    fun provideGatewayAgentBindingRepository(database: FerngeistDatabase): GatewayAgentBindingRepository =
        GatewayAgentBindingRepositoryImpl(database.gatewayAgentBindingDao())

    /** Combines server + gateway repositories into a launchable target registry. */
    @Provides
    @Singleton
    fun provideLaunchableTargetRepository(
        serverRepository: ServerRepository,
        gatewaySourceRepository: GatewaySourceRepository,
        gatewayAgentBindingRepository: GatewayAgentBindingRepository,
    ): LaunchableTargetRepository =
        LaunchableTargetRepositoryImpl(
            serverRepository = serverRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            gatewayAgentBindingRepository = gatewayAgentBindingRepository,
        )

    @Provides
    @Singleton
    fun provideTurnTimingRepository(database: FerngeistDatabase): TurnTimingRepository =
        TurnTimingRepositoryImpl(database.turnTimingDao())

    /** Persists per-target session settings (cwd, etc.). */
    @Provides
    @Singleton
    fun provideLaunchableTargetSessionSettingsRepository(
        database: FerngeistDatabase,
    ): LaunchableTargetSessionSettingsRepository =
        LaunchableTargetSessionSettingsRepositoryImpl(database.launchableTargetSessionSettingsDao())
}
