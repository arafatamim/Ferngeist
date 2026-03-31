package com.tamimarafat.ferngeist.feature.serverlist.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthEnvValueModule {

    @Binds
    @Singleton
    abstract fun bindAuthEnvValueStore(
        impl: EncryptedAuthEnvValueStore,
    ): AuthEnvValueStore
}
