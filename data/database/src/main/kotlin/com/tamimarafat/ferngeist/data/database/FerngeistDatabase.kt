package com.tamimarafat.ferngeist.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tamimarafat.ferngeist.data.database.dao.ServerDao
import com.tamimarafat.ferngeist.data.database.dao.SessionDao
import com.tamimarafat.ferngeist.data.database.entity.ServerEntity
import com.tamimarafat.ferngeist.data.database.entity.SessionEntity

@Database(
    entities = [
        ServerEntity::class,
        SessionEntity::class,
    ],
    version = 3,
    exportSchema = false
)
abstract class FerngeistDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val DATABASE_NAME = "ferngeist_database"
    }
}
