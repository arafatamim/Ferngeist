package com.tamimarafat.ferngeist.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Records the live gateway session id on session rows (v14 → v15).
 *
 * Lives beside [FerngeistDatabase] rather than in the DI module so the
 * migration can be exercised by [FerngeistMigrationTest] against the exported
 * schemas in `schemas/`.
 */
val migration14To15: Migration =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN gatewaySessionId TEXT")
        }
    }

/**
 * Records the registry logo URL reported by the gateway on agent bindings
 * (v15 → v16). Null for custom and embedded agents, which have no registry
 * entry and therefore no logo.
 */
val migration15To16: Migration =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE gateway_agent_bindings ADD COLUMN icon TEXT")
        }
    }

/** Keeps each turn's start and finish for a week, since a replay carries no times (v16 → v17). */
val migration16To17: Migration =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `turn_timings` (`sessionId` TEXT NOT NULL, " +
                    "`turnIndex` INTEGER NOT NULL, `promptHash` INTEGER NOT NULL, " +
                    "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sessionId`, `turnIndex`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_turn_timings_finishedAt` ON `turn_timings` (`finishedAt`)")
        }
    }

/** Listing snapshots replace chat records; timings keep only a uniquely identifiable owner. */
val migration17To18: Migration =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE `turn_timings_new` (`serverId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                    "`turnIndex` INTEGER NOT NULL, `promptHash` INTEGER NOT NULL, " +
                    "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`serverId`, `sessionId`, `turnIndex`))",
            )
            db.execSQL(
                "INSERT INTO turn_timings_new " +
                    "SELECT owners.serverId, timing.sessionId, timing.turnIndex, timing.promptHash, " +
                    "timing.startedAt, timing.finishedAt FROM turn_timings AS timing JOIN " +
                    "(SELECT sessionId, MIN(serverId) AS serverId FROM sessions " +
                    "GROUP BY sessionId HAVING COUNT(DISTINCT serverId) = 1) AS owners " +
                    "ON timing.sessionId = owners.sessionId",
            )
            db.execSQL("DROP TABLE turn_timings")
            db.execSQL("ALTER TABLE turn_timings_new RENAME TO turn_timings")
            db.execSQL("CREATE INDEX `index_turn_timings_finishedAt` ON `turn_timings` (`finishedAt`)")
            db.execSQL("DROP TABLE sessions")
            db.execSQL("ALTER TABLE gateway_agent_bindings ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            // Synthetic times preserve insertion order for bindings whose real creation time was never stored.
            db.execSQL("UPDATE gateway_agent_bindings SET createdAt = rowid")
        }
    }
