package com.tamimarafat.ferngeist.feature.chat

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface SwitcherHintStoreModule {
    @Binds
    @Singleton
    fun bindSwitcherHintStore(impl: DataStoreSwitcherHintStore): SwitcherHintStore
}
