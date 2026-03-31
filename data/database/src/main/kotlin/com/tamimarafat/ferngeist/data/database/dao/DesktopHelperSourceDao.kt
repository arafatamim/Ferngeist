package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tamimarafat.ferngeist.data.database.entity.DesktopHelperSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DesktopHelperSourceDao {
    @Query("SELECT * FROM desktop_helper_sources ORDER BY name ASC")
    fun getAllHelpers(): Flow<List<DesktopHelperSourceEntity>>

    @Query("SELECT * FROM desktop_helper_sources WHERE id = :id")
    suspend fun getHelperById(id: String): DesktopHelperSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHelper(helper: DesktopHelperSourceEntity)

    @Update
    suspend fun updateHelper(helper: DesktopHelperSourceEntity)

    @Query("DELETE FROM desktop_helper_sources WHERE id = :id")
    suspend fun deleteHelperById(id: String)
}
