package com.tamimarafat.ferngeist.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tamimarafat.ferngeist.acp.bridge.connection.AndroidConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.facade.AcpChatSessionFacadeFactory
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import com.tamimarafat.ferngeist.data.database.crypto.CredentialEncryptor
import com.tamimarafat.ferngeist.data.database.migration14To15
import com.tamimarafat.ferngeist.data.database.migration15To16
import com.tamimarafat.ferngeist.data.database.migration16To17
import com.tamimarafat.ferngeist.data.database.migration17To18
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.push.AndroidUnifiedPushClient
import com.tamimarafat.ferngeist.push.PushRegistrar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Bounds whole-call REST latency so network-backed spinners can never hang. */
private const val HTTP_REQUEST_TIMEOUT_MS = 30_000L

/** Bounds TCP/TLS establishment for gateway REST calls. */
private const val HTTP_CONNECT_TIMEOUT_MS = 10_000L

/** Bounds inactivity between response packets for gateway REST calls. */
private const val HTTP_SOCKET_TIMEOUT_MS = 30_000L

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Provides the Room database with all current migrations registered. */
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
                migration1To2,
                migration2To3,
                migration3To4,
                migration4To5,
                migration5To6,
                migration6To7,
                migration7To8,
                migration8To9,
                migration9To10,
                migration10To11,
                migration11To12,
                migration12To13,
                migration13To14,
                migration14To15,
                migration15To16,
                migration16To17,
                migration17To18,
            ).fallbackToDestructiveMigration(false)
            .build()

    /** Provides encryptor for sensitive database fields. */
    @Provides
    @Singleton
    fun provideCredentialEncryptor(
        @ApplicationContext context: Context,
    ): CredentialEncryptor = CredentialEncryptor(context)

    /** Application-scoped scope for process-wide observers (registry aggregates). */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Single network-availability observer shared by the hub and the screens. */
    @Provides
    @Singleton
    fun provideConnectivityObserver(
        @ApplicationContext context: Context,
    ): ConnectivityObserver = AndroidConnectivityObserver(context)

    /** Tracks every open chat's connection; owns every ACP manager lifetime in the process. */
    @Provides
    @Singleton
    fun provideChatConnectionHub(
        connectivityObserver: ConnectivityObserver,
        gatewayRepository: GatewayRepository,
        applicationScope: CoroutineScope,
    ): ChatConnectionHub =
        ChatConnectionHub(
            gatewayRepository = gatewayRepository,
            scope = applicationScope,
            connectivityObserver = connectivityObserver,
        )

    /** Supplies the chat-session facade factory backed by ACP. */
    @Provides
    @Singleton
    fun provideChatSessionFacadeFactory(
        launchableTargetRepository: LaunchableTargetRepository,
        gatewaySourceRepository: GatewaySourceRepository,
        gatewayRepository: GatewayRepository,
        hub: ChatConnectionHub,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ChatSessionFacadeFactory =
        AcpChatSessionFacadeFactory(
            hub = hub,
            launchableTargetRepository = launchableTargetRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            gatewayRepository = gatewayRepository,
            ioDispatcher = ioDispatcher,
        )

    /** Keeps every paired gateway subscribed to this device's Web Push notifications. */
    @Provides
    @Singleton
    fun providePushRegistrar(
        @ApplicationContext context: Context,
        gatewayRepository: GatewayRepository,
        gatewaySourceRepository: GatewaySourceRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): PushRegistrar =
        PushRegistrar(
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            unifiedPush = AndroidUnifiedPushClient(context),
            scope = CoroutineScope(SupervisorJob() + ioDispatcher),
        )

    /** Provides a shared JSON configuration for network and persistence layers. */
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
        }

    /** Provides a singleton Ktor HTTP client with bounded timeouts so REST-backed spinners can never hang. */
    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient =
        // Android engine (platform HttpURLConnection), not CIO: CIO dials a fresh
        // connection for every request that is not a pipelined GET and closes it after
        // the response, so each gateway call in a chat open paid a TCP connect (and a TLS
        // handshake over HTTPS). HttpURLConnection pools keep-alive connections.
        HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = HTTP_REQUEST_TIMEOUT_MS
                connectTimeoutMillis = HTTP_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = HTTP_SOCKET_TIMEOUT_MS
            }
        }
}

private val migration1To2 =
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

private val migration2To3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `messages`")
        }
    }

private val migration3To4 =
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

private val migration4To5 =
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

private val migration5To6 =
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

private val migration6To7 =
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

private val sqlCreateGatewayAgentBindings =
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
    """.trimIndent()

private const val SQL_INDEX_GATEWAY_AGENT_BINDINGS_GATEWAY_SOURCE_ID =
    "CREATE INDEX IF NOT EXISTS `index_gateway_agent_bindings_gatewaySourceId` " +
        "ON `gateway_agent_bindings` (`gatewaySourceId`)"

private val sqlInsertGatewayAgentBindings =
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
    """.trimIndent()

private val sqlCreateServersNew =
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

private val sqlInsertServersNew =
    """
    INSERT INTO `servers_new` (`id`, `name`, `scheme`, `host`, `token`, `workingDirectory`, `preferredAuthMethodId`)
    SELECT `id`, `name`, `scheme`, `host`, `token`, `workingDirectory`, `preferredAuthMethodId`
    FROM `servers`
    WHERE `sourceKind` = 'MANUAL_ACP'
    """.trimIndent()

private val sqlCreateSessionsNew =
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

private val sqlInsertSessionsNew =
    """
    INSERT INTO `sessions_new` (`sessionId`, `serverId`, `title`, `cwd`, `updatedAt`)
    SELECT `sessionId`, `serverId`, `title`, `cwd`, `updatedAt` FROM `sessions`
    """.trimIndent()

private val sqlCreateLaunchableTargetSettings =
    """
    CREATE TABLE IF NOT EXISTS `launchable_target_session_settings` (
      `targetId` TEXT NOT NULL,
      `cwd` TEXT,
      PRIMARY KEY(`targetId`)
    )
    """.trimIndent()

private val sqlInsertTargetSettingsFromServers =
    """
    INSERT INTO `launchable_target_session_settings` (`targetId`, `cwd`)
    SELECT `id`, `workingDirectory` FROM `servers`
    """.trimIndent()

private val sqlInsertTargetSettingsFromBindings =
    """
    INSERT INTO `launchable_target_session_settings` (`targetId`, `cwd`)
    SELECT `id`, `workingDirectory` FROM `gateway_agent_bindings`
    """.trimIndent()

private val sql8To9CreateServersNew =
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
    """.trimIndent()

private val sql8To9InsertServersNew =
    """
    INSERT INTO `servers_new` (`id`, `name`, `scheme`, `host`, `token`, `preferredAuthMethodId`)
    SELECT `id`, `name`, `scheme`, `host`, `token`, `preferredAuthMethodId` FROM `servers`
    """.trimIndent()

private val sqlCreateGatewayAgentBindingsNew =
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
    """.trimIndent()

private const val SQL_INDEX_GATEWAY_AGENT_BINDINGS_NEW =
    "CREATE INDEX IF NOT EXISTS `index_gateway_agent_bindings_new_gatewaySourceId` " +
        "ON `gateway_agent_bindings_new` (`gatewaySourceId`)"

private val sqlInsertGatewayAgentBindingsNew =
    """
    INSERT INTO `gateway_agent_bindings_new` (`id`, `name`, `gatewaySourceId`, `agentId`, `preferredAuthMethodId`)
    SELECT `id`, `name`, `gatewaySourceId`, `agentId`, `preferredAuthMethodId` FROM `gateway_agent_bindings`
    """.trimIndent()

private val migration7To8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(sqlCreateGatewayAgentBindings)
            db.execSQL(SQL_INDEX_GATEWAY_AGENT_BINDINGS_GATEWAY_SOURCE_ID)
            db.execSQL(sqlInsertGatewayAgentBindings)
            db.execSQL(sqlCreateServersNew)
            db.execSQL(sqlInsertServersNew)
            db.execSQL("DROP TABLE `servers`")
            db.execSQL("ALTER TABLE `servers_new` RENAME TO `servers`")
            db.execSQL(sqlCreateSessionsNew)
            db.execSQL(sqlInsertSessionsNew)
            db.execSQL("DROP TABLE `sessions`")
            db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_serverId` ON `sessions` (`serverId`)")
        }
    }

private val migration8To9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(sqlCreateLaunchableTargetSettings)
            db.execSQL(sqlInsertTargetSettingsFromServers)
            db.execSQL(sqlInsertTargetSettingsFromBindings)
            db.execSQL(sql8To9CreateServersNew)
            db.execSQL(sql8To9InsertServersNew)
            db.execSQL("DROP TABLE `servers`")
            db.execSQL("ALTER TABLE `servers_new` RENAME TO `servers`")
            db.execSQL(sqlCreateGatewayAgentBindingsNew)
            db.execSQL(SQL_INDEX_GATEWAY_AGENT_BINDINGS_NEW)
            db.execSQL(sqlInsertGatewayAgentBindingsNew)
            db.execSQL("DROP TABLE `gateway_agent_bindings`")
            db.execSQL("ALTER TABLE `gateway_agent_bindings_new` RENAME TO `gateway_agent_bindings`")
        }
    }

// Repairs a previously-missing step: the database version was bumped 11 -> 12 during the
// "rename helper to gateway" refactor without a registered migration, which crashed any
// upgrade from a v11 database. The rename was code-only (no schema change), so this is a
// no-op that simply makes the migration chain valid again.
private val migration11To12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema change.
        }
    }

private val migration12To13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Adds the gateway-owned identifier used to translate push payloads' serverId
            // back to the local GatewaySource.id for deep-linking.
            db.execSQL(
                """
                ALTER TABLE `gateway_sources`
                ADD COLUMN `gatewayId` TEXT
                """.trimIndent(),
            )
        }
    }

private val migration13To14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema change — version bump only
        }
    }

private val migration10To11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Credentials are now encrypted at the repository layer via CredentialEncryptor.
            // No schema changes; existing plaintext credentials remain unencrypted in Room
            // until the corresponding server/gateway row is next written (add/update), at
            // which point CredentialEncryptor.encrypt() encrypts and persists them.
            // Users who never edit a saved server after this migration will retain plaintext
            // credentials in the database backup.
        }
    }

private val migration9To10 =
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
