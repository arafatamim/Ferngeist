package com.tamimarafat.ferngeist.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tamimarafat.ferngeist.data.database.dao.GatewayAgentBindingDao
import com.tamimarafat.ferngeist.data.database.dao.GatewaySourceDao
import com.tamimarafat.ferngeist.data.database.dao.LaunchableTargetSessionSettingsDao
import com.tamimarafat.ferngeist.data.database.dao.ServerDao
import com.tamimarafat.ferngeist.data.database.dao.SessionDao
import com.tamimarafat.ferngeist.data.database.entity.GatewaySourceEntity
import com.tamimarafat.ferngeist.data.database.entity.GatewayAgentBindingEntity
import com.tamimarafat.ferngeist.data.database.entity.LaunchableTargetSessionSettingsEntity
import com.tamimarafat.ferngeist.data.database.entity.ServerEntity
import com.tamimarafat.ferngeist.data.database.entity.SessionEntity

@Database(
    entities = [
        ServerEntity::class,
        GatewaySourceEntity::class,
        GatewayAgentBindingEntity::class,
        SessionEntity::class,
        LaunchableTargetSessionSettingsEntity::class,
    ],
    version = 12,
    exportSchema = false
)
abstract class FerngeistDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun gatewaySourceDao(): GatewaySourceDao
    abstract fun gatewayAgentBindingDao(): GatewayAgentBindingDao
    abstract fun sessionDao(): SessionDao
    abstract fun launchableTargetSessionSettingsDao(): LaunchableTargetSessionSettingsDao

    companion object {
        const val DATABASE_NAME = "ferngeist_database"
    }
}
