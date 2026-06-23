package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tamimarafat.ferngeist.data.database.entity.PaseoSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PaseoSourceDao {
    @Query("SELECT * FROM paseo_sources ORDER BY name ASC")
    fun getAllSources(): Flow<List<PaseoSourceEntity>>

    @Query("SELECT * FROM paseo_sources WHERE id = :id")
    suspend fun getSourceById(id: String): PaseoSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: PaseoSourceEntity)

    @Update
    suspend fun updateSource(source: PaseoSourceEntity)

    @Query("DELETE FROM paseo_sources WHERE id = :id")
    suspend fun deleteSourceById(id: String)
}
