package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
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

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    /**
     * Atomic upsert: inserts the row or, on conflict, updates every summary
     * column while keeping the stored [SessionEntity.gatewaySessionId] when
     * [gatewaySessionId] is null. A gateway session recorded concurrently by
     * [updateGatewaySessionId] therefore survives a stale summary write —
     * there is no read-then-REPLACE window to clobber it. The mapping can
     * only be cleared explicitly through [updateGatewaySessionId].
     */
    @Query(
        "INSERT INTO sessions (sessionId, serverId, title, cwd, updatedAt, gatewaySessionId) " +
            "VALUES (:sessionId, :serverId, :title, :cwd, :updatedAt, :gatewaySessionId) " +
            "ON CONFLICT(sessionId) DO UPDATE SET " +
            "serverId = excluded.serverId, title = excluded.title, cwd = excluded.cwd, " +
            "updatedAt = excluded.updatedAt, " +
            "gatewaySessionId = COALESCE(excluded.gatewaySessionId, sessions.gatewaySessionId)",
    )
    suspend fun upsertSession(
        sessionId: String,
        serverId: String,
        title: String?,
        cwd: String?,
        updatedAt: Long?,
        gatewaySessionId: String?,
    )

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
    ): Int

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM sessions WHERE serverId = :serverId")
    suspend fun deleteSessionsByServerId(serverId: String)

    @Query("DELETE FROM sessions WHERE serverId = :serverId AND sessionId NOT IN (:sessionIds)")
    suspend fun deleteSessionsNotIn(
        serverId: String,
        sessionIds: List<String>,
    )

    /**
     * Atomically replaces every session for [serverId] with [sessions].
     * Wrapped in a single transaction so the Room invalidation flow emits the
     * complete new list exactly once instead of an intermediate empty list
     * that flashes the loading spinner over the list during refresh.
     *
     * Surviving rows are upserted in place rather than deleted-and-reinserted
     * so the [upsertSession] COALESCE keeps any concurrently recorded
     * [SessionEntity.gatewaySessionId]; only rows absent from [sessions] are
     * deleted.
     */
    @Transaction
    suspend fun replaceSessions(
        serverId: String,
        sessions: List<SessionEntity>,
    ) {
        if (sessions.isEmpty()) {
            deleteSessionsByServerId(serverId)
            return
        }
        sessions.forEach { session ->
            upsertSession(
                sessionId = session.sessionId,
                serverId = session.serverId,
                title = session.title,
                cwd = session.cwd,
                updatedAt = session.updatedAt,
                gatewaySessionId = session.gatewaySessionId,
            )
        }
        deleteSessionsNotIn(serverId, sessions.map { it.sessionId })
    }
}
