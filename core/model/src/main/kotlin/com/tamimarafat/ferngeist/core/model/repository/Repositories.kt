package com.tamimarafat.ferngeist.core.model.repository

import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    fun getServers(): Flow<List<ServerConfig>>
    suspend fun addServer(config: ServerConfig)
    suspend fun updateServer(config: ServerConfig)
    suspend fun deleteServer(id: String)
    suspend fun getServer(id: String): ServerConfig?
}

interface SessionRepository {
    fun getSessions(serverId: String): Flow<List<SessionSummary>>
    suspend fun upsertSession(serverId: String, summary: SessionSummary)
    suspend fun deleteSession(serverId: String, sessionId: String)
    suspend fun clearSessions(serverId: String)
}
