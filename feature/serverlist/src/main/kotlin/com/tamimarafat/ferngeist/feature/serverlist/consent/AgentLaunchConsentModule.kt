package com.tamimarafat.ferngeist.feature.serverlist.consent

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentLaunchConsentModule {
    @Binds
    @Singleton
    abstract fun bindAgentLaunchConsentStore(impl: AgentLaunchConsentStoreImpl): AgentLaunchConsentStore
}
