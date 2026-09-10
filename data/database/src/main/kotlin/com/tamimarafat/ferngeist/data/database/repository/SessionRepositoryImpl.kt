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
        // REPLACE-insert would clear gatewaySessionId; carry the stored value forward.
        val existing = sessionDao.getSessionById(summary.id)
        sessionDao.insertSession(
            SessionEntity(
                sessionId = summary.id,
                serverId = serverId,
                title = summary.title,
                cwd = summary.cwd,
                updatedAt = summary.updatedAt,
                gatewaySessionId = summary.gatewaySessionId ?: existing?.gatewaySessionId,
            ),
        )
    }

    override suspend fun setGatewaySessionId(
        serverId: String,
        sessionId: String,
        gatewaySessionId: String?,
    ) {
        sessionDao.updateGatewaySessionId(
            sessionId = sessionId,
            serverId = serverId,
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
        val existing = sessionDao.getSessionsSnapshot(serverId).associateBy { it.sessionId }
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
                        gatewaySessionId = summary.gatewaySessionId ?: existing[summary.id]?.gatewaySessionId,
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
