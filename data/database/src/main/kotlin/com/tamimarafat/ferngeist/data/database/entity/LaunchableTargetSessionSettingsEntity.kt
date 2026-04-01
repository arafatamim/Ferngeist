package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "launchable_target_session_settings")
data class LaunchableTargetSessionSettingsEntity(
    @PrimaryKey val targetId: String,
    val cwd: String?,
)
