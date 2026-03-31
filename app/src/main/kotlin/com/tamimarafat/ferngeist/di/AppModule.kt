package com.tamimarafat.ferngeist.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AndroidConnectivityObserver
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.core.model.repository.HelperAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import com.tamimarafat.ferngeist.data.database.repository.DesktopHelperSourceRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.HelperAgentBindingRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.LaunchableTargetRepositoryImpl
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
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
    }
    
    @Provides
    @Singleton
    fun provideServerRepository(database: FerngeistDatabase): ServerRepository {
        return ServerRepositoryImpl(database.serverDao())
    }

    @Provides
    @Singleton
    fun provideDesktopHelperSourceRepository(database: FerngeistDatabase): DesktopHelperSourceRepository {
        return DesktopHelperSourceRepositoryImpl(database.desktopHelperSourceDao())
    }

    @Provides
    @Singleton
    fun provideHelperAgentBindingRepository(database: FerngeistDatabase): HelperAgentBindingRepository {
        return HelperAgentBindingRepositoryImpl(database.helperAgentBindingDao())
    }

    @Provides
    @Singleton
    fun provideLaunchableTargetRepository(
        serverRepository: ServerRepository,
        helperSourceRepository: DesktopHelperSourceRepository,
        helperAgentBindingRepository: HelperAgentBindingRepository,
    ): LaunchableTargetRepository {
        return LaunchableTargetRepositoryImpl(
            serverRepository = serverRepository,
            helperSourceRepository = helperSourceRepository,
            helperAgentBindingRepository = helperAgentBindingRepository,
        )
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

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `desktop_helper_sources` (
              `id` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `scheme` TEXT NOT NULL,
              `host` TEXT NOT NULL,
              `helperCredential` TEXT NOT NULL,
              `helperCredentialExpiresAt` INTEGER,
              `helperRemoteMode` TEXT,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `desktop_helper_sources` (
              `id`,
              `name`,
              `scheme`,
              `host`,
              `helperCredential`,
              `helperCredentialExpiresAt`,
              `helperRemoteMode`
            )
            SELECT
              `id`,
              `name`,
              `scheme`,
              `host`,
              `helperCredential`,
              `helperCredentialExpiresAt`,
              `helperRemoteMode`
            FROM `servers`
            WHERE `sourceKind` = 'DESKTOP_HELPER' AND (`selectedAgentId` IS NULL OR TRIM(`selectedAgentId`) = '')
            """.trimIndent()
        )
        db.execSQL(
            """
            DELETE FROM `servers`
            WHERE `sourceKind` = 'DESKTOP_HELPER' AND (`selectedAgentId` IS NULL OR TRIM(`selectedAgentId`) = '')
            """.trimIndent()
        )
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `helper_agent_bindings` (
              `id` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `helperSourceId` TEXT NOT NULL,
              `agentId` TEXT NOT NULL,
              `workingDirectory` TEXT NOT NULL,
              `preferredAuthMethodId` TEXT,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`helperSourceId`) REFERENCES `desktop_helper_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_helper_agent_bindings_helperSourceId` ON `helper_agent_bindings` (`helperSourceId`)")
        db.execSQL(
            """
            INSERT INTO `helper_agent_bindings` (
              `id`,
              `name`,
              `helperSourceId`,
              `agentId`,
              `workingDirectory`,
              `preferredAuthMethodId`
            )
            SELECT
              `id`,
              `name`,
              `helperSourceId`,
              `selectedAgentId`,
              `workingDirectory`,
              `preferredAuthMethodId`
            FROM `servers`
            WHERE `sourceKind` = 'DESKTOP_HELPER' AND `helperSourceId` IS NOT NULL AND TRIM(`helperSourceId`) != ''
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `servers_new` (
              `id` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `scheme` TEXT NOT NULL,
              `host` TEXT NOT NULL,
              `token` TEXT NOT NULL,
              `workingDirectory` TEXT NOT NULL,
              `preferredAuthMethodId` TEXT,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `servers_new` (`id`, `name`, `scheme`, `host`, `token`, `workingDirectory`, `preferredAuthMethodId`)
            SELECT `id`, `name`, `scheme`, `host`, `token`, `workingDirectory`, `preferredAuthMethodId`
            FROM `servers`
            WHERE `sourceKind` = 'MANUAL_ACP'
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `servers`")
        db.execSQL("ALTER TABLE `servers_new` RENAME TO `servers`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sessions_new` (
              `sessionId` TEXT NOT NULL,
              `serverId` TEXT NOT NULL,
              `title` TEXT,
              `cwd` TEXT,
              `updatedAt` INTEGER,
              PRIMARY KEY(`sessionId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `sessions_new` (`sessionId`, `serverId`, `title`, `cwd`, `updatedAt`)
            SELECT `sessionId`, `serverId`, `title`, `cwd`, `updatedAt` FROM `sessions`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `sessions`")
        db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_serverId` ON `sessions` (`serverId`)")
    }
}
