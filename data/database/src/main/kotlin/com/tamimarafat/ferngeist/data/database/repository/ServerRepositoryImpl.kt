package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.data.database.crypto.CredentialEncryptor
import com.tamimarafat.ferngeist.data.database.dao.ServerDao
import com.tamimarafat.ferngeist.data.database.entity.ServerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerRepositoryImpl(
    private val serverDao: ServerDao,
    private val credentialEncryptor: CredentialEncryptor,
) : ServerRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getServers(): Flow<List<ServerConfig>> {
        return serverDao.getAllServers().map { entities ->
            withContext(Dispatchers.IO) {
                entities.mapNotNull { entity ->
                    toDomainOrCleanUp(entity)
                }
            }
        }
    }

    override suspend fun addServer(config: ServerConfig) {
        withContext(Dispatchers.IO) {
            serverDao.insertServer(config.toEntity())
        }
    }

    override suspend fun updateServer(config: ServerConfig) {
        withContext(Dispatchers.IO) {
            serverDao.updateServer(config.toEntity())
        }
    }

    override suspend fun deleteServer(id: String) {
        withContext(Dispatchers.IO) {
            serverDao.deleteServerById(id)
            credentialEncryptor.delete(CredentialEncryptor.serverTokenKey(id))
        }
    }

    override suspend fun getServer(id: String): ServerConfig? {
        return withContext(Dispatchers.IO) {
            val entity = serverDao.getServerById(id) ?: return@withContext null
            toDomainOrCleanUp(entity)
        }
    }

    private fun toDomainOrCleanUp(entity: ServerEntity): ServerConfig? {
        val tokenKey = CredentialEncryptor.serverTokenKey(entity.id)
        return try {
            ServerConfig(
                id = entity.id,
                name = entity.name,
                scheme = entity.scheme,
                host = entity.host,
                token = credentialEncryptor.decrypt(entity.token, tokenKey),
                preferredAuthMethodId = entity.preferredAuthMethodId,
            )
        } catch (_: CredentialEncryptor.CredentialUnavailableException) {
            scope.launch {
                serverDao.deleteServerById(entity.id)
                credentialEncryptor.delete(tokenKey)
            }
            null
        }
    }

    private fun ServerConfig.toEntity(): ServerEntity {
        val tokenKey = CredentialEncryptor.serverTokenKey(id)
        return ServerEntity(
            id = id,
            name = name,
            scheme = scheme,
            host = host,
            token = credentialEncryptor.encrypt(token, tokenKey),
            preferredAuthMethodId = preferredAuthMethodId,
        )
    }
}
