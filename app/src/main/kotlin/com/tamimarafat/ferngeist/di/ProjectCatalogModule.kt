package com.tamimarafat.ferngeist.di

import android.content.Context
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSnapshotStore
import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjects
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

/** Wires the home's [ProjectCatalog] to the gateway client and its snapshots in `noBackupFilesDir`. */
@Module
@InstallIn(SingletonComponent::class)
object ProjectCatalogModule {
    @Provides
    @Singleton
    fun provideProjectSnapshotStore(
        @ApplicationContext context: Context,
        json: Json,
    ): ProjectSnapshotStore = ProjectSnapshotStore(File(context.noBackupFilesDir, "projects"), json)

    @Provides
    @Singleton
    fun provideProjectCatalog(
        gatewayProjects: GatewayProjects,
        repositories: LaunchRepositories,
        store: ProjectSnapshotStore,
        applicationScope: CoroutineScope,
    ): ProjectCatalog = ProjectCatalog(gatewayProjects, repositories, store, applicationScope)
}
