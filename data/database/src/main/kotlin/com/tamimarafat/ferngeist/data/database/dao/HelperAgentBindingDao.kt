package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tamimarafat.ferngeist.data.database.entity.HelperAgentBindingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HelperAgentBindingDao {
    @Query("SELECT * FROM helper_agent_bindings ORDER BY name ASC")
    fun getAllBindings(): Flow<List<HelperAgentBindingEntity>>

    @Query("SELECT * FROM helper_agent_bindings WHERE id = :id")
    suspend fun getBindingById(id: String): HelperAgentBindingEntity?

    @Query("SELECT * FROM helper_agent_bindings WHERE helperSourceId = :helperId ORDER BY name ASC")
    suspend fun getBindingsForHelper(helperId: String): List<HelperAgentBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBinding(binding: HelperAgentBindingEntity)

    @Update
    suspend fun updateBinding(binding: HelperAgentBindingEntity)

    @Query("DELETE FROM helper_agent_bindings WHERE id = :id")
    suspend fun deleteBindingById(id: String)
}
