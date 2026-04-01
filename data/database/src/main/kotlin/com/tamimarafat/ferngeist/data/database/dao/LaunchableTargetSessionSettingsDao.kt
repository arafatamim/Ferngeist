package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tamimarafat.ferngeist.data.database.entity.LaunchableTargetSessionSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LaunchableTargetSessionSettingsDao {
    @Query("SELECT * FROM launchable_target_session_settings WHERE targetId = :targetId")
    fun getSettingsByTargetId(targetId: String): Flow<LaunchableTargetSessionSettingsEntity?>

    @Query("SELECT * FROM launchable_target_session_settings WHERE targetId = :targetId")
    suspend fun getSettingsByTargetIdBlocking(targetId: String): LaunchableTargetSessionSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: LaunchableTargetSessionSettingsEntity)

    @Query("DELETE FROM launchable_target_session_settings WHERE targetId = :targetId")
    suspend fun deleteSettingsByTargetId(targetId: String)
}
