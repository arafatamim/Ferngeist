package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface RecentCwdModule {
    @Binds
    @Singleton
    fun bindRecentCwdStore(impl: DataStoreRecentCwdStore): RecentCwdStore
}
