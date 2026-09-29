package com.tamimarafat.ferngeist.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the v14 → v15 upgrade path against the exported schemas in `schemas/`.
 *
 * Runs on the JVM under Robolectric: CI has no emulator, and a broken migration
 * here means crashes or data loss for every existing install, not a test miss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FerngeistMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            FerngeistDatabase::class.java,
        )

    @Test
    fun migrate14To15_keepsExistingSessionsAndAddsGatewaySessionId() {
        helper.createDatabase(TEST_DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO sessions (sessionId, serverId, title, cwd, updatedAt) " +
                    "VALUES ('session-1', 'server-1', 'Existing chat', '/work', 1700000000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 15, true, MIGRATION_14_15)
        migrated.use { db ->
            db
                .query(
                    "SELECT sessionId, serverId, title, cwd, updatedAt, gatewaySessionId FROM sessions",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("session-1", cursor.getString(0))
                    assertEquals("server-1", cursor.getString(1))
                    assertEquals("Existing chat", cursor.getString(2))
                    assertEquals("/work", cursor.getString(3))
                    assertEquals(1_700_000_000_000L, cursor.getLong(4))
                    assertNull(cursor.getString(5))
                    assertFalse(cursor.moveToNext())
                }
        }
    }

    @Test
    fun migrate15To16_keepsBindingsAndAddsNullIcon() {
        helper.createDatabase(TEST_DB, 15).use { db ->
            db.execSQL(
                "INSERT INTO gateway_sources (id, name, scheme, host, gatewayCredential) " +
                    "VALUES ('gw-1', 'gw', 'wss', '10.0.0.1', 'cred')",
            )
            db.execSQL(
                "INSERT INTO gateway_agent_bindings " +
                    "(id, name, gatewaySourceId, agentId, preferredAuthMethodId) " +
                    "VALUES ('b-1', 'Codex', 'gw-1', 'codex-acp', NULL)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 16, true, MIGRATION_15_16)
        migrated.use { db ->
            db
                .query(
                    "SELECT id, name, gatewaySourceId, agentId, preferredAuthMethodId, icon " +
                        "FROM gateway_agent_bindings",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("b-1", cursor.getString(0))
                    assertEquals("Codex", cursor.getString(1))
                    assertEquals("gw-1", cursor.getString(2))
                    assertEquals("codex-acp", cursor.getString(3))
                    assertNull(cursor.getString(4))
                    // Pre-existing bindings have no registry provenance, so the
                    // column must land null rather than a fabricated URL.
                    assertNull(cursor.getString(5))
                    assertFalse(cursor.moveToNext())
                }
        }
    }

    private companion object {
        /**
         * Absolute path on purpose: Room 2.8 resolves the database file through
         * the context while its SQLite driver keeps the name it was configured
         * with, and a bare name trips that check under Robolectric
         * ("This driver is configured to open a database named ... but ... was
         * requested"). Passing the resolved path keeps both sides identical.
         */
        val TEST_DB: String
            get() = File(RuntimeEnvironment.getApplication().cacheDir, "ferngeist-migration-test.db").absolutePath
    }
}
