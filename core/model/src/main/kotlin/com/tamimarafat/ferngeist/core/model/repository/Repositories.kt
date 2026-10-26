package com.tamimarafat.ferngeist.core.model.repository

import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.LaunchableTargetSessionSettings
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.core.model.TurnTiming
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

    /** Resolves a gateway by its gateway-owned id (as carried in push payloads). */
    suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource?
}

/** Resolves the local ID from a gateway-owned ID (as carried in push payloads). */
suspend fun GatewaySourceRepository.resolveLocalId(gatewayId: String): String? = getGatewayByGatewayId(gatewayId)?.id

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

    suspend fun updatePreferredAuthMethod(
        targetId: String,
        methodId: String,
    )

    suspend fun deleteTarget(id: String)
}

/** Chats as the agents last reported them, plus the ones this phone created since; newest first. */
interface ChatListings {
    fun getSessions(serverId: String): Flow<List<SessionSummary>>

    fun getRecentSessions(limit: Int): Flow<List<SessionSummary>>
}

/** Snapshot-backed listings and memory-only chats; authoritative listings alone reach disk. */
interface ChatCatalog : ChatListings {
    suspend fun getSession(
        serverId: String,
        sessionId: String,
    ): SessionSummary?

    fun chatCreated(
        serverId: String,
        summary: SessionSummary,
    )

    suspend fun recordListing(
        serverId: String,
        sessions: List<SessionSummary>,
    )

    suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    )

    suspend fun clearSessions(serverId: String)
}

/** Turn times kept for a while, since a session's replay carries none. */
interface TurnTimingRepository {
    /** The session's recorded turns; also drops turns older than the retention window. */
    suspend fun getTimings(
        serverId: String,
        sessionId: String,
    ): Map<TurnKey, TurnTiming>

    suspend fun record(
        serverId: String,
        sessionId: String,
        key: TurnKey,
        timing: TurnTiming,
    )
}

interface LaunchableTargetSessionSettingsRepository {
    fun getSettings(targetId: String): Flow<LaunchableTargetSessionSettings>

    suspend fun updateCwd(
        targetId: String,
        cwd: String,
    )

    suspend fun deleteSettings(targetId: String)
}
