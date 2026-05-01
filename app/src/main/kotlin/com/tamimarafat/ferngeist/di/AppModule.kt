package com.tamimarafat.ferngeist.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AndroidConnectivityObserver
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import com.tamimarafat.ferngeist.data.database.crypto.CredentialEncryptor
import com.tamimarafat.ferngeist.data.database.repository.GatewayAgentBindingRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.GatewaySourceRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.LaunchableTargetRepositoryImpl
import com.tamimarafat.ferngeist.data.database.repository.LaunchableTargetSessionSettingsRepositoryImpl
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
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): FerngeistDatabase =
        Room
            .databaseBuilder(
                context,
                FerngeistDatabase::class.java,
                FerngeistDatabase.DATABASE_NAME,
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
            ).fallbackToDestructiveMigration(false)
            .build()

    @Provides
    @Singleton
    fun provideServerRepository(
        database: FerngeistDatabase,
        credentialEncryptor: CredentialEncryptor,
    ): ServerRepository = ServerRepositoryImpl(database.serverDao(), credentialEncryptor)

    @Provides
    @Singleton
    fun provideGatewaySourceRepository(
        database: FerngeistDatabase,
        credentialEncryptor: CredentialEncryptor,
    ): GatewaySourceRepository = GatewaySourceRepositoryImpl(database.gatewaySourceDao(), credentialEncryptor)

    @Provides
    @Singleton
    fun provideGatewayAgentBindingRepository(database: FerngeistDatabase): GatewayAgentBindingRepository =
        GatewayAgentBindingRepositoryImpl(database.gatewayAgentBindingDao())

    @Provides
    @Singleton
    fun provideLaunchableTargetRepository(
        serverRepository: ServerRepository,
        gatewaySourceRepository: GatewaySourceRepository,
        gatewayAgentBindingRepository: GatewayAgentBindingRepository,
    ): LaunchableTargetRepository =
        LaunchableTargetRepositoryImpl(
            serverRepository = serverRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            gatewayAgentBindingRepository = gatewayAgentBindingRepository,
        )

    @Provides
    @Singleton
    fun provideSessionRepository(database: FerngeistDatabase): SessionRepository =
        SessionRepositoryImpl(database.sessionDao())

    @Provides
    @Singleton
    fun provideLaunchableTargetSessionSettingsRepository(
        database: FerngeistDatabase,
    ): LaunchableTargetSessionSettingsRepository =
        LaunchableTargetSessionSettingsRepositoryImpl(database.launchableTargetSessionSettingsDao())

    @Provides
    @Singleton
    fun provideCredentialEncryptor(
        @ApplicationContext context: Context,
    ): CredentialEncryptor = CredentialEncryptor(context)

    @Provides
    @Singleton
    fun provideAcpConnectionManager(
        @ApplicationContext context: Context,
    ): AcpConnectionManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val connectivityObserver = AndroidConnectivityObserver(context)
        return AcpConnectionManager(connectivityObserver, scope)
    }

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
        }

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(CIO)
}

private val MIGRATION_1_2 =
    object : Migration(1, 2) {
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
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `servers_new` (`id`, `name`, `scheme`, `host`, `token`, `workingDirectory`)
                SELECT `id`, `name`, `scheme`, `host`, `token`, `workingDirectory` FROM `servers`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `servers`")
            db.execSQL("ALTER TABLE `servers_new` RENAME TO `servers`")
        }
    }

private val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `messages`")
        }
    }

private val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE `servers`
                ADD COLUMN `preferredAuthMethodId` TEXT
                """.trimIndent(),
            )
        }
    }

private val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE `servers`
                ADD COLUMN `sourceKind` TEXT NOT NULL DEFAULT 'MANUAL_ACP'
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE `servers`
                ADD COLUMN `gatewayCredential` TEXT NOT NULL DEFAULT ''
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE `servers`
                ADD COLUMN `gatewayCredentialExpiresAt` INTEGER
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE `servers`
                ADD COLUMN `gatewayRemoteMode` TEXT
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE `servers`
                ADD COLUMN `selectedAgentId` TEXT
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE `servers`
                ADD COLUMN `selectedAgentName` TEXT
                """.trimIndent(),
            )
        }
    }

private val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE `servers`
                ADD COLUMN `gatewaySourceId` TEXT
                """.trimIndent(),
            )
        }
    }

private val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `gateway_sources` (
                  `id` TEXT NOT NULL,
                  `name` TEXT NOT NULL,
                  `scheme` TEXT NOT NULL,
                  `host` TEXT NOT NULL,
                  `gatewayCredential` TEXT NOT NULL,
                  `gatewayCredentialExpiresAt` INTEGER,
                  `gatewayRemoteMode` TEXT,
                  PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `gateway_sources` (
                  `id`,
                  `name`,
                  `scheme`,
                  `host`,
                  `gatewayCredential`,
                  `gatewayCredentialExpiresAt`,
                  `gatewayRemoteMode`
                )
                SELECT
                  `id`,
                  `name`,
                  `scheme`,
                  `host`,
                  `gatewayCredential`,
                  `gatewayCredentialExpiresAt`,
                  `gatewayRemoteMode`
                FROM `servers`
                WHERE `sourceKind` = 'DESKTOP_HELPER' AND (`selectedAgentId` IS NULL OR TRIM(`selectedAgentId`) = '')
                """.trimIndent(),
            )
            db.execSQL(
                """
                DELETE FROM `servers`
                WHERE `sourceKind` = 'DESKTOP_HELPER' AND (`selectedAgentId` IS NULL OR TRIM(`selectedAgentId`) = '')
                """.trimIndent(),
            )
        }
    }

private val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `gateway_agent_bindings` (
                  `id` TEXT NOT NULL,
                  `name` TEXT NOT NULL,
                  `gatewaySourceId` TEXT NOT NULL,
                  `agentId` TEXT NOT NULL,
                  `workingDirectory` TEXT NOT NULL,
                  `preferredAuthMethodId` TEXT,
                  PRIMARY KEY(`id`),
                  FOREIGN KEY(`gatewaySourceId`) REFERENCES `gateway_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_gateway_agent_bindings_gatewaySourceId` " +
                    "ON `gateway_agent_bindings` (`gatewaySourceId`)",
            )
            db.execSQL(
                """
                INSERT INTO `gateway_agent_bindings` (
                  `id`,
                  `name`,
                  `gatewaySourceId`,
                  `agentId`,
                  `workingDirectory`,
                  `preferredAuthMethodId`
                )
                SELECT
                  `id`,
                  `name`,
                  `gatewaySourceId`,
                  `selectedAgentId`,
                  `workingDirectory`,
                  `preferredAuthMethodId`
                FROM `servers`
                WHERE `sourceKind` = 'DESKTOP_HELPER' AND `gatewaySourceId` IS NOT NULL AND TRIM(`gatewaySourceId`) != ''
                """.trimIndent(),
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
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `servers_new` (`id`, `name`, `scheme`, `host`, `token`, `workingDirectory`, `preferredAuthMethodId`)
                SELECT `id`, `name`, `scheme`, `host`, `token`, `workingDirectory`, `preferredAuthMethodId`
                FROM `servers`
                WHERE `sourceKind` = 'MANUAL_ACP'
                """.trimIndent(),
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
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `sessions_new` (`sessionId`, `serverId`, `title`, `cwd`, `updatedAt`)
                SELECT `sessionId`, `serverId`, `title`, `cwd`, `updatedAt` FROM `sessions`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `sessions`")
            db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_serverId` ON `sessions` (`serverId`)")
        }
    }

private val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `launchable_target_session_settings` (
                  `targetId` TEXT NOT NULL,
                  `cwd` TEXT,
                  PRIMARY KEY(`targetId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `launchable_target_session_settings` (`targetId`, `cwd`)
                SELECT `id`, `workingDirectory` FROM `servers`
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `launchable_target_session_settings` (`targetId`, `cwd`)
                SELECT `id`, `workingDirectory` FROM `gateway_agent_bindings`
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `servers_new` (
                  `id` TEXT NOT NULL,
                  `name` TEXT NOT NULL,
                  `scheme` TEXT NOT NULL,
                  `host` TEXT NOT NULL,
                  `token` TEXT NOT NULL,
                  `preferredAuthMethodId` TEXT,
                  PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `servers_new` (`id`, `name`, `scheme`, `host`, `token`, `preferredAuthMethodId`)
                SELECT `id`, `name`, `scheme`, `host`, `token`, `preferredAuthMethodId` FROM `servers`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `servers`")
            db.execSQL("ALTER TABLE `servers_new` RENAME TO `servers`")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `gateway_agent_bindings_new` (
                  `id` TEXT NOT NULL,
                  `name` TEXT NOT NULL,
                  `gatewaySourceId` TEXT NOT NULL,
                  `agentId` TEXT NOT NULL,
                  `preferredAuthMethodId` TEXT,
                  PRIMARY KEY(`id`),
                  FOREIGN KEY(`gatewaySourceId`) REFERENCES `gateway_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_gateway_agent_bindings_new_gatewaySourceId` " +
                    "ON `gateway_agent_bindings_new` (`gatewaySourceId`)",
            )
            db.execSQL(
                """
                INSERT INTO `gateway_agent_bindings_new` (`id`, `name`, `gatewaySourceId`, `agentId`, `preferredAuthMethodId`)
                SELECT `id`, `name`, `gatewaySourceId`, `agentId`, `preferredAuthMethodId` FROM `gateway_agent_bindings`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `gateway_agent_bindings`")
            db.execSQL("ALTER TABLE `gateway_agent_bindings_new` RENAME TO `gateway_agent_bindings`")
        }
    }

private val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Credentials are now encrypted at the repository layer via CredentialEncryptor.
            // No schema changes; existing plaintext credentials remain unencrypted in Room
            // until the corresponding server/gateway row is next written (add/update), at
            // which point CredentialEncryptor.encrypt() rewrites them to EncryptedSharedPreferences.
            // Users who never edit a saved server after this migration will retain plaintext
            // credentials in the database backup.
        }
    }

private val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `launchable_target_session_settings_new` (
                  `targetId` TEXT NOT NULL,
                  `cwd` TEXT,
                  PRIMARY KEY(`targetId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `launchable_target_session_settings_new` (`targetId`, `cwd`)
                SELECT `targetId`, `cwd` FROM `launchable_target_session_settings`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `launchable_target_session_settings`")
            db.execSQL(
                "ALTER TABLE `launchable_target_session_settings_new` RENAME TO `launchable_target_session_settings`",
            )
        }
    }
