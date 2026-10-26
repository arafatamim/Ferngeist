package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** In-memory session repository stub. */
class FakeChatCatalog : ChatCatalog {
    private val sessionsFlows = mutableMapOf<String, MutableStateFlow<List<SessionSummary>>>()

    /** Test hook: sets the session list emitted for a server. */
    fun setSessions(
        serverId: String,
        sessions: List<SessionSummary>,
    ) {
        sessionsFlows.getOrPut(serverId) { MutableStateFlow(emptyList()) }.value = sessions
    }

    override fun getSessions(serverId: String): Flow<List<SessionSummary>> =
        sessionsFlows.getOrPut(serverId) { MutableStateFlow(emptyList()) }

    override fun getRecentSessions(limit: Int): Flow<List<SessionSummary>> = emptyFlow()

    override suspend fun getSession(
        serverId: String,
        sessionId: String,
    ): SessionSummary? = null

    override fun chatCreated(
        serverId: String,
        summary: SessionSummary,
    ) = Unit

    override suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    ) = Unit

    override suspend fun clearSessions(serverId: String) = Unit

    override suspend fun recordListing(
        serverId: String,
        sessions: List<SessionSummary>,
    ) = Unit
}
