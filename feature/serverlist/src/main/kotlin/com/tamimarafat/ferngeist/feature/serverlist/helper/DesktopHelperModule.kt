package com.tamimarafat.ferngeist.feature.serverlist.helper

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DesktopHelperModule {

    @Binds
    @Singleton
    abstract fun bindDesktopHelperRepository(
        impl: DesktopHelperRepositoryImpl,
    ): DesktopHelperRepository
}
