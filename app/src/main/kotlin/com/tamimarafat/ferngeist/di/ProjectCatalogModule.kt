package com.tamimarafat.ferngeist.di

import android.content.Context
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectDiscoveryConsent
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSnapshotStore
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.ChatListings
import com.tamimarafat.ferngeist.feature.serverlist.consent.AgentLaunchConsentStore
import com.tamimarafat.ferngeist.gateway.GatewayClock
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStream
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

/** Wires the home's [ProjectCatalog] to the gateway client and its snapshots in `noBackupFilesDir`. */
@Module
@InstallIn(SingletonComponent::class)
object ProjectCatalogModule {
    @Provides
    @ProjectDiscoveryConsent
    fun provideProjectDiscoveryConsent(store: AgentLaunchConsentStore): Flow<Set<String>> = store.consentedKeys

    @Provides
    @Singleton
    fun provideProjectSnapshotStore(
        @ApplicationContext context: Context,
        json: Json,
    ): ProjectSnapshotStore = ProjectSnapshotStore(File(context.noBackupFilesDir, "projects"), json)

    @Provides
    @Singleton
    fun provideGatewayTurnStream(
        httpClient: HttpClient,
        json: Json,
        clock: GatewayClock,
    ): GatewayTurnStream = GatewayTurnStream(httpClient, json, clock)

    @Provides
    fun provideChatCatalog(catalog: ProjectCatalog): ChatCatalog = catalog

    /** The hero, recents and chat switcher read chats through the catalog. */
    @Provides
    fun provideChatListings(catalog: ProjectCatalog): ChatListings = catalog
}
