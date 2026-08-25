package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tamimarafat.ferngeist.data.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE serverId = :serverId ORDER BY updatedAt DESC")
    fun getSessionsByServerId(serverId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentSessions(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE serverId = :serverId")
    suspend fun getSessionsSnapshot(serverId: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title WHERE sessionId = :sessionId AND serverId = :serverId")
    suspend fun updateSessionTitle(
        sessionId: String,
        serverId: String,
        title: String,
    )

    @Query(
        "UPDATE sessions SET gatewaySessionId = :gatewaySessionId WHERE sessionId = :sessionId AND serverId = :serverId",
    )
    suspend fun updateGatewaySessionId(
        sessionId: String,
        serverId: String,
        gatewaySessionId: String?,
    )

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM sessions WHERE serverId = :serverId")
    suspend fun deleteSessionsByServerId(serverId: String)

    /**
     * Atomically replaces every session for [serverId] with [sessions].
     * Wrapped in a single transaction so the Room invalidation flow emits the
     * complete new list exactly once — a clear-then-insert loop would emit an
     * intermediate empty list that flashes the loading spinner over the list
     * during refresh.
     */
    @Transaction
    suspend fun replaceSessions(
        serverId: String,
        sessions: List<SessionEntity>,
    ) {
        deleteSessionsByServerId(serverId)
        sessions.forEach { insertSession(it) }
    }
}
