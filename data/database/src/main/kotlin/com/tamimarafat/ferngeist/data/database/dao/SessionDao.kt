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

    /**
     * Clears [gatewaySessionId] from every other row on [serverId] that still
     * claims it. A gateway session is leased to exactly one chat, so when the
     * true owner records its session any stale claimant must release it —
     * otherwise two chats resume one session and fight over the lease.
     */
    @Query(
        "UPDATE sessions SET gatewaySessionId = NULL WHERE serverId = :serverId " +
            "AND gatewaySessionId = :gatewaySessionId AND sessionId != :sessionId",
    )
    suspend fun clearGatewaySessionClaim(
        serverId: String,
        gatewaySessionId: String,
        sessionId: String,
    ): Int

    /**
     * Records [gatewaySessionId] on one row and releases every other row still
     * claiming it, as a single transaction.
     *
     * The clear has to be a separate statement from the update (it is a
     * "everything except this row" predicate), which leaves a window: two chats
     * claiming the same gateway session in an interleaved order — update A,
     * update B, clear-for-A drops B, clear-for-B drops A — leave **no** row
     * owning the session, so neither chat resumes it and it is leaked. Running
     * both statements in one transaction serialises the two claims, making the
     * last writer the sole owner.
     */
    @Transaction
    suspend fun setGatewaySessionId(
        serverId: String,
        sessionId: String,
        gatewaySessionId: String?,
    ) {
        val updated =
            updateGatewaySessionId(
                sessionId = sessionId,
                serverId = serverId,
                gatewaySessionId = gatewaySessionId,
            )
        if (updated == 0 && gatewaySessionId != null) {
            // Create-on-arrival chats can attach before any list refresh has
            // inserted the row; seed a minimal one so the mapping survives.
            upsertSession(
                sessionId = sessionId,
                serverId = serverId,
                title = null,
                cwd = null,
                updatedAt = null,
                gatewaySessionId = gatewaySessionId,
            )
        }
        if (gatewaySessionId != null) {
            clearGatewaySessionClaim(
                serverId = serverId,
                gatewaySessionId = gatewaySessionId,
                sessionId = sessionId,
            )
        }
    }

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
