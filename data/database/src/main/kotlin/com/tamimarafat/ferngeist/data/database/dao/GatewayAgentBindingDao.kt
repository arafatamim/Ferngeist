package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tamimarafat.ferngeist.data.database.entity.GatewayAgentBindingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GatewayAgentBindingDao {
    @Query("SELECT * FROM gateway_agent_bindings ORDER BY name ASC")
    fun getAllBindings(): Flow<List<GatewayAgentBindingEntity>>

    @Query("SELECT * FROM gateway_agent_bindings WHERE id = :id")
    suspend fun getBindingById(id: String): GatewayAgentBindingEntity?

    @Query("SELECT * FROM gateway_agent_bindings WHERE gatewaySourceId = :gatewayId ORDER BY name ASC")
    suspend fun getBindingsForGateway(gatewayId: String): List<GatewayAgentBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBinding(binding: GatewayAgentBindingEntity)

    @Update
    suspend fun updateBinding(binding: GatewayAgentBindingEntity)

    @Query("DELETE FROM gateway_agent_bindings WHERE id = :id")
    suspend fun deleteBindingById(id: String)
}
