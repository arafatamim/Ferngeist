package com.tamimarafat.ferngeist.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tamimarafat.ferngeist.data.database.dao.GatewayAgentBindingDao
import com.tamimarafat.ferngeist.data.database.dao.GatewaySourceDao
import com.tamimarafat.ferngeist.data.database.dao.LaunchableTargetSessionSettingsDao
import com.tamimarafat.ferngeist.data.database.dao.ServerDao
import com.tamimarafat.ferngeist.data.database.dao.TurnTimingDao
import com.tamimarafat.ferngeist.data.database.entity.GatewayAgentBindingEntity
import com.tamimarafat.ferngeist.data.database.entity.GatewaySourceEntity
import com.tamimarafat.ferngeist.data.database.entity.LaunchableTargetSessionSettingsEntity
import com.tamimarafat.ferngeist.data.database.entity.ServerEntity
import com.tamimarafat.ferngeist.data.database.entity.TurnTimingEntity

@Database(
    entities = [
        ServerEntity::class,
        GatewaySourceEntity::class,
        GatewayAgentBindingEntity::class,
        LaunchableTargetSessionSettingsEntity::class,
        TurnTimingEntity::class,
    ],
    version = 18,
    exportSchema = true,
)
abstract class FerngeistDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    abstract fun gatewaySourceDao(): GatewaySourceDao

    abstract fun gatewayAgentBindingDao(): GatewayAgentBindingDao

    abstract fun launchableTargetSessionSettingsDao(): LaunchableTargetSessionSettingsDao

    abstract fun turnTimingDao(): TurnTimingDao

    companion object {
        const val DATABASE_NAME = "ferngeist_database"
    }
}
