package com.tamimarafat.ferngeist.feature.chat

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SwitcherHintStoreModule {
    @Binds
    @Singleton
    abstract fun bindSwitcherHintStore(impl: DataStoreSwitcherHintStore): SwitcherHintStore
}
