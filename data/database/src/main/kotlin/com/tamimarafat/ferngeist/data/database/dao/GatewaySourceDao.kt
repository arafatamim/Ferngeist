package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tamimarafat.ferngeist.data.database.entity.GatewaySourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GatewaySourceDao {
    @Query("SELECT * FROM gateway_sources ORDER BY name ASC")
    fun getAllGateways(): Flow<List<GatewaySourceEntity>>

    @Query("SELECT * FROM gateway_sources WHERE id = :id")
    suspend fun getGatewayById(id: String): GatewaySourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGateway(gateway: GatewaySourceEntity)

    @Update
    suspend fun updateGateway(gateway: GatewaySourceEntity)

    @Query("DELETE FROM gateway_sources WHERE id = :id")
    suspend fun deleteGatewayById(id: String)
}
