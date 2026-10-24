package com.tamimarafat.ferngeist.data.database.repository

import androidx.room.withTransaction
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import com.tamimarafat.ferngeist.data.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SessionRepositoryImpl(
    private val database: FerngeistDatabase,
) : SessionRepository {
    private val sessionDao = database.sessionDao()
    private val leaseDao = database.gatewaySessionLeaseDao()

    override fun getSessions(serverId: String): Flow<List<SessionSummary>> =
        sessionDao
            .getSessionsByServerId(serverId)
            .distinctUntilChanged()
            .map { entities ->
                entities.map { it.toSummary() }
            }

    override fun getRecentSessions(limit: Int): Flow<List<SessionSummary>> =
        sessionDao
            .getRecentSessions(limit)
            .distinctUntilChanged()
            .map { entities ->
                entities.map { it.toSummary() }
            }

    override suspend fun getSession(
        serverId: String,
        sessionId: String,
    ): SessionSummary? = sessionDao.getSessionById(sessionId)?.toSummary()

    override suspend fun upsertSession(
        serverId: String,
        summary: SessionSummary,
    ) {
        // The DAO's COALESCE upsert preserves a concurrently recorded
        // gatewaySessionId, so no pre-read is needed (a read-then-REPLACE
        // sequence could clobber it with a stale null).
        sessionDao.upsertSession(
            sessionId = summary.id,
            serverId = serverId,
            title = summary.title,
            cwd = summary.cwd,
            updatedAt = summary.updatedAt,
            gatewaySessionId = summary.gatewaySessionId,
        )
    }

    override suspend fun setGatewaySessionId(
        serverId: String,
        sessionId: String,
        gatewaySessionId: String?,
    ) {
        // One gateway session serves one chat, so this both records the mapping
        // and makes this row the sole owner — any other row pointing at the same
        // session releases it, since a stale claim would otherwise resume the
        // session on a cold start and put two chats on one lease.
        //
        // The claim and the release have to be separate statements (the release is an
        // "everything except this row" predicate), which leaves a window: two chats
        // claiming the same gateway session in an interleaved order — update A,
        // update B, clear-for-A drops B, clear-for-B drops A — leave **no** row
        // owning the session, so neither chat resumes it and it is leaked. Running
        // both statements in one transaction serialises the two claims, making the
        // last writer the sole owner.
        database.withTransaction {
            val updated =
                leaseDao.updateGatewaySessionId(
                    sessionId = sessionId,
                    serverId = serverId,
                    gatewaySessionId = gatewaySessionId,
                )
            if (updated == 0 && gatewaySessionId != null) {
                // Create-on-arrival chats can attach before any list refresh has
                // inserted the row; seed a minimal one so the mapping survives.
                sessionDao.upsertSession(
                    sessionId = sessionId,
                    serverId = serverId,
                    title = null,
                    cwd = null,
                    updatedAt = null,
                    gatewaySessionId = gatewaySessionId,
                )
            }
            if (gatewaySessionId != null) {
                leaseDao.clearGatewaySessionClaim(
                    serverId = serverId,
                    gatewaySessionId = gatewaySessionId,
                    sessionId = sessionId,
                )
            }
        }
    }

    override suspend fun updateSessionTitle(
        serverId: String,
        sessionId: String,
        title: String,
    ) {
        sessionDao.updateSessionTitle(sessionId = sessionId, serverId = serverId, title = title)
    }

    override suspend fun touchSession(
        serverId: String,
        sessionId: String,
        updatedAt: Long,
    ) {
        sessionDao.touchSession(sessionId = sessionId, serverId = serverId, updatedAt = updatedAt)
    }

    override suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    ) {
        sessionDao.deleteSessionById(sessionId)
    }

    override suspend fun clearStaleGatewaySessions(
        serverId: String,
        liveGatewaySessionIds: Set<String>,
    ): Int =
        if (liveGatewaySessionIds.isEmpty()) {
            leaseDao.clearAllGatewaySessions(serverId)
        } else {
            leaseDao.clearStaleGatewaySessions(serverId, liveGatewaySessionIds)
        }

    override suspend fun clearSessions(serverId: String) {
        sessionDao.deleteSessionsByServerId(serverId)
    }

    override suspend fun replaceSessions(
        serverId: String,
        sessions: List<SessionSummary>,
    ) {
        // The DAO upserts survivors in place (COALESCE keeps a concurrently
        // recorded gatewaySessionId) and deletes only rows absent from the
        // refresh, so no snapshot pre-read is needed and a stale-null insert
        // cannot clobber a live mapping.
        sessionDao.replaceSessions(
            serverId = serverId,
            sessions =
                sessions.map { summary ->
                    SessionEntity(
                        sessionId = summary.id,
                        serverId = serverId,
                        title = summary.title,
                        cwd = summary.cwd,
                        updatedAt = summary.updatedAt,
                        gatewaySessionId = summary.gatewaySessionId,
                    )
                },
        )
    }

    private fun SessionEntity.toSummary() =
        SessionSummary(
            id = sessionId,
            title = title,
            cwd = cwd,
            updatedAt = updatedAt,
            serverId = serverId,
            gatewaySessionId = gatewaySessionId,
        )
}
