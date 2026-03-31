package com.tamimarafat.ferngeist.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AndroidConnectivityObserver
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import com.tamimarafat.ferngeist.data.database.repository.ServerRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.SessionRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FerngeistDatabase {
        return Room.databaseBuilder(
            context,
            FerngeistDatabase::class.java,
            FerngeistDatabase.DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
    }
    
    @Provides
    @Singleton
    fun provideServerRepository(database: FerngeistDatabase): ServerRepository {
        return ServerRepositoryImpl(database.serverDao(), database.sessionDao())
    }
    
    @Provides
    @Singleton
    fun provideSessionRepository(database: FerngeistDatabase): SessionRepository {
        return SessionRepositoryImpl(database.sessionDao())
    }
    
    @Provides
    @Singleton
    fun provideAcpConnectionManager(@ApplicationContext context: Context): AcpConnectionManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val connectivityObserver = AndroidConnectivityObserver(context)
        return AcpConnectionManager(connectivityObserver, scope)
    }

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
        }
    }

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient {
        return HttpClient(CIO)
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `servers_new` (
              `id` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `scheme` TEXT NOT NULL,
              `host` TEXT NOT NULL,
              `token` TEXT NOT NULL,
              `workingDirectory` TEXT NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `servers_new` (`id`, `name`, `scheme`, `host`, `token`, `workingDirectory`)
            SELECT `id`, `name`, `scheme`, `host`, `token`, `workingDirectory` FROM `servers`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `servers`")
        db.execSQL("ALTER TABLE `servers_new` RENAME TO `servers`")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `messages`")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE `servers`
            ADD COLUMN `preferredAuthMethodId` TEXT
            """.trimIndent()
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE `servers`
            ADD COLUMN `sourceKind` TEXT NOT NULL DEFAULT 'MANUAL_ACP'
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE `servers`
            ADD COLUMN `helperCredential` TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE `servers`
            ADD COLUMN `helperCredentialExpiresAt` INTEGER
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE `servers`
            ADD COLUMN `helperRemoteMode` TEXT
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE `servers`
            ADD COLUMN `selectedAgentId` TEXT
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE `servers`
            ADD COLUMN `selectedAgentName` TEXT
            """.trimIndent()
        )
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE `servers`
            ADD COLUMN `helperSourceId` TEXT
            """.trimIndent()
        )
    }
}
