package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.ServerSourceKind
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.data.database.dao.ServerDao
import com.tamimarafat.ferngeist.data.database.dao.SessionDao
import com.tamimarafat.ferngeist.data.database.entity.ServerEntity
import com.tamimarafat.ferngeist.data.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ServerRepositoryImpl(
    private val serverDao: ServerDao,
    private val sessionDao: SessionDao,
) : ServerRepository {

    override fun getServers(): Flow<List<ServerConfig>> {
        return serverDao.getAllServers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun addServer(config: ServerConfig) {
        serverDao.insertServer(config.toEntity())
    }

    override suspend fun updateServer(config: ServerConfig) {
        serverDao.updateServer(config.toEntity())
    }

    override suspend fun deleteServer(id: String) {
        serverDao.deleteServerById(id)
    }

    override suspend fun getServer(id: String): ServerConfig? {
        return serverDao.getServerById(id)?.toDomain()
    }
}

private fun ServerEntity.toDomain(): ServerConfig {
    return ServerConfig(
        id = id,
        name = name,
        sourceKind = sourceKind.toServerSourceKind(),
        scheme = scheme,
        host = host,
        token = token,
        workingDirectory = workingDirectory,
        preferredAuthMethodId = preferredAuthMethodId,
        helperCredential = helperCredential,
        helperCredentialExpiresAt = helperCredentialExpiresAt,
        helperRemoteMode = helperRemoteMode,
        helperSourceId = helperSourceId,
        selectedAgentId = selectedAgentId,
        selectedAgentName = selectedAgentName,
    )
}

private fun ServerConfig.toEntity(): ServerEntity {
    return ServerEntity(
        id = id,
        name = name,
        sourceKind = sourceKind.name,
        scheme = scheme,
        host = host,
        token = token,
        workingDirectory = workingDirectory,
        preferredAuthMethodId = preferredAuthMethodId,
        helperCredential = helperCredential,
        helperCredentialExpiresAt = helperCredentialExpiresAt,
        helperRemoteMode = helperRemoteMode,
        helperSourceId = helperSourceId,
        selectedAgentId = selectedAgentId,
        selectedAgentName = selectedAgentName,
    )
}

private fun String.toServerSourceKind(): ServerSourceKind {
    return runCatching { ServerSourceKind.valueOf(this) }
        .getOrDefault(ServerSourceKind.MANUAL_ACP)
}
