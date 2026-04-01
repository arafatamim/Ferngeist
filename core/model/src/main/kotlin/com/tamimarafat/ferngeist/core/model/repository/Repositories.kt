package com.tamimarafat.ferngeist.core.model.repository

import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.HelperAgentBinding
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

interface DesktopHelperSourceRepository {
    fun getHelpers(): Flow<List<DesktopHelperSource>>
    suspend fun addHelper(helper: DesktopHelperSource)
    suspend fun updateHelper(helper: DesktopHelperSource)
    suspend fun deleteHelper(id: String)
    suspend fun getHelper(id: String): DesktopHelperSource?
}

interface HelperAgentBindingRepository {
    fun getBindings(): Flow<List<HelperAgentBinding>>
    suspend fun addBinding(binding: HelperAgentBinding)
    suspend fun updateBinding(binding: HelperAgentBinding)
    suspend fun deleteBinding(id: String)
    suspend fun getBinding(id: String): HelperAgentBinding?
    suspend fun getBindingsForHelper(helperId: String): List<HelperAgentBinding>
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
