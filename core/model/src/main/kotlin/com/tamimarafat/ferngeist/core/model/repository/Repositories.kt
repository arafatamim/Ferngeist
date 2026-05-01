package com.tamimarafat.ferngeist.core.model.repository

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.LaunchableTargetSessionSettings
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

interface GatewaySourceRepository {
    fun getGateways(): Flow<List<GatewaySource>>
    suspend fun addGateway(gateway: GatewaySource)
    suspend fun updateGateway(gateway: GatewaySource)
    suspend fun deleteGateway(id: String)
    suspend fun getGateway(id: String): GatewaySource?
}

interface GatewayAgentBindingRepository {
    fun getBindings(): Flow<List<GatewayAgentBinding>>
    suspend fun addBinding(binding: GatewayAgentBinding)
    suspend fun updateBinding(binding: GatewayAgentBinding)
    suspend fun deleteBinding(id: String)
    suspend fun getBinding(id: String): GatewayAgentBinding?
    suspend fun getBindingsForGateway(gatewayId: String): List<GatewayAgentBinding>
}

interface LaunchableTargetRepository {
    fun getTargets(): Flow<List<LaunchableTarget>>
    suspend fun getTarget(id: String): LaunchableTarget?
    suspend fun updatePreferredAuthMethod(targetId: String, methodId: String)
    suspend fun deleteTarget(id: String)
}

interface SessionRepository {
    fun getSessions(serverId: String): Flow<List<SessionSummary>>
    suspend fun upsertSession(serverId: String, summary: SessionSummary)
    suspend fun deleteSession(serverId: String, sessionId: String)
    suspend fun clearSessions(serverId: String)
}

interface LaunchableTargetSessionSettingsRepository {
    fun getSettings(targetId: String): Flow<LaunchableTargetSessionSettings>
    suspend fun getSettingsBlocking(targetId: String): LaunchableTargetSessionSettings?
    suspend fun updateCwd(targetId: String, cwd: String)
    suspend fun deleteSettings(targetId: String)
}
