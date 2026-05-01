package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.data.database.dao.SessionDao
import com.tamimarafat.ferngeist.data.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SessionRepositoryImpl(
    private val sessionDao: SessionDao,
) : SessionRepository {
    override fun getSessions(serverId: String): Flow<List<SessionSummary>> =
        sessionDao.getSessionsByServerId(serverId).map { entities ->
            entities.map { entity ->
                SessionSummary(
                    id = entity.sessionId,
                    title = entity.title,
                    cwd = entity.cwd,
                    updatedAt = entity.updatedAt,
                )
            }
        }

    override suspend fun upsertSession(
        serverId: String,
        summary: SessionSummary,
    ) {
        sessionDao.insertSession(
            SessionEntity(
                sessionId = summary.id,
                serverId = serverId,
                title = summary.title,
                cwd = summary.cwd,
                updatedAt = summary.updatedAt,
            ),
        )
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
}
