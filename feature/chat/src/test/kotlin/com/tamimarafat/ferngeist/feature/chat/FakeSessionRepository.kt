package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** In-memory session repository stub. */
class FakeSessionRepository : SessionRepository {
    var getSessionResult: SessionSummary? = null
    var getSessionCalls: Int = 0

    var updateTitleCalls: MutableList<Triple<String, String, String>> = mutableListOf()

    override suspend fun updateSessionTitle(
        serverId: String,
        sessionId: String,
        title: String,
    ) {
        updateTitleCalls.add(Triple(serverId, sessionId, title))
    }

    override fun getSessions(serverId: String): Flow<List<SessionSummary>> = emptyFlow()

    override fun getRecentSessions(limit: Int): Flow<List<SessionSummary>> = emptyFlow()

    override suspend fun getSession(
        serverId: String,
        sessionId: String,
    ): SessionSummary? {
        getSessionCalls += 1
        return getSessionResult
    }

    override suspend fun upsertSession(
        serverId: String,
        summary: SessionSummary,
    ) = Unit

    override suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    ) = Unit

    override suspend fun clearSessions(serverId: String) = Unit

    override suspend fun replaceSessions(
        serverId: String,
        sessions: List<SessionSummary>,
    ) = Unit
}
