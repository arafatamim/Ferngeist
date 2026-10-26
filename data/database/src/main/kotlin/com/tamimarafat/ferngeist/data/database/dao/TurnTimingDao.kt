package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.tamimarafat.ferngeist.data.database.entity.TurnTimingEntity

@Dao
interface TurnTimingDao {
    @Query("SELECT * FROM turn_timings WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun getForSession(
        serverId: String,
        sessionId: String,
    ): List<TurnTimingEntity>

    @Upsert
    suspend fun upsert(timing: TurnTimingEntity)

    @Query("DELETE FROM turn_timings WHERE finishedAt < :before")
    suspend fun deleteFinishedBefore(before: Long)
}
