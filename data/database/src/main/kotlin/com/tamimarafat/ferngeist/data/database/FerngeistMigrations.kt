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
val MIGRATION_14_15: Migration =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN gatewaySessionId TEXT")
        }
    }
