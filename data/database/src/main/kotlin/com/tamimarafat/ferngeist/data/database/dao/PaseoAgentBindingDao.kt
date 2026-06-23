package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tamimarafat.ferngeist.data.database.entity.PaseoAgentBindingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PaseoAgentBindingDao {
    @Query("SELECT * FROM paseo_agent_bindings ORDER BY name ASC")
    fun getAllBindings(): Flow<List<PaseoAgentBindingEntity>>

    @Query("SELECT * FROM paseo_agent_bindings WHERE id = :id")
    suspend fun getBindingById(id: String): PaseoAgentBindingEntity?

    @Query("SELECT * FROM paseo_agent_bindings WHERE paseoSourceId = :sourceId ORDER BY name ASC")
    suspend fun getBindingsForSource(sourceId: String): List<PaseoAgentBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBinding(binding: PaseoAgentBindingEntity)

    @Update
    suspend fun updateBinding(binding: PaseoAgentBindingEntity)

    @Query("DELETE FROM paseo_agent_bindings WHERE id = :id")
    suspend fun deleteBindingById(id: String)
}
