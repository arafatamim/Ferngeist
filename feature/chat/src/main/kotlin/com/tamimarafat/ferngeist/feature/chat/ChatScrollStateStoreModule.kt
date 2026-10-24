package com.tamimarafat.ferngeist.feature.chat

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface ChatScrollStateStoreModule {
    @Binds
    @Singleton
    fun bindChatScrollStateStore(impl: DataStoreChatScrollStateStore): ChatScrollStateStore
}
