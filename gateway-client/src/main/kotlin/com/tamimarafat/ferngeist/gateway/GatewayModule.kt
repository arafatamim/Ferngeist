package com.tamimarafat.ferngeist.gateway

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GatewayModule {
    @Binds
    @Singleton
    abstract fun bindGatewayRepository(impl: GatewayRepositoryImpl): GatewayRepository
}
