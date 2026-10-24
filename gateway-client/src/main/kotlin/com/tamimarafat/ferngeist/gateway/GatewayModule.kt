package com.tamimarafat.ferngeist.gateway

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface GatewayModule {
    @Binds
    @Singleton
    fun bindGatewayRepository(impl: GatewayRepositoryImpl): GatewayRepository
}
