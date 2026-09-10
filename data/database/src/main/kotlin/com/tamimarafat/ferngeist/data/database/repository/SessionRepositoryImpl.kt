package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.data.database.dao.SessionDao
import com.tamimarafat.ferngeist.data.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SessionRepositoryImpl(
    private val sessionDao: SessionDao,
) : SessionRepository {
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
        // session on a cold start and put two chats on one lease. Atomicity is
        // the DAO's job: see [SessionDao.setGatewaySessionId].
        sessionDao.setGatewaySessionId(
            serverId = serverId,
            sessionId = sessionId,
            gatewaySessionId = gatewaySessionId,
        )
    }

    override suspend fun updateSessionTitle(
        serverId: String,
        sessionId: String,
        title: String,
    ) {
        sessionDao.updateSessionTitle(sessionId = sessionId, serverId = serverId, title = title)
    }

    override suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    ) {
        sessionDao.deleteSessionById(sessionId)
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
