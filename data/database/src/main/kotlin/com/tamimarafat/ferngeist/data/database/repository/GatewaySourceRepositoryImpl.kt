package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.data.database.crypto.CredentialEncryptor
import com.tamimarafat.ferngeist.data.database.dao.GatewaySourceDao
import com.tamimarafat.ferngeist.data.database.entity.GatewaySourceEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GatewaySourceRepositoryImpl(
    private val gatewayDao: GatewaySourceDao,
    private val credentialEncryptor: CredentialEncryptor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GatewaySourceRepository {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    override fun getGateways(): Flow<List<GatewaySource>> =
        gatewayDao.getAllGateways().map { entities ->
            withContext(ioDispatcher) {
                entities.mapNotNull { entity ->
                    toDomainOrCleanUp(entity)
                }
            }
        }

    override suspend fun addGateway(gateway: GatewaySource) {
        withContext(ioDispatcher) {
            gatewayDao.insertGateway(gateway.toEntity())
        }
    }

    override suspend fun updateGateway(gateway: GatewaySource) {
        withContext(ioDispatcher) {
            gatewayDao.updateGateway(gateway.toEntity())
        }
    }

    override suspend fun deleteGateway(id: String) {
        withContext(ioDispatcher) {
            // Bindings cascade away; snapshot projections hide chats without a bound target.
            gatewayDao.deleteGatewayById(id)
            credentialEncryptor.delete(CredentialEncryptor.gatewayCredentialKey(id))
        }
    }

    override suspend fun getGateway(id: String): GatewaySource? {
        return withContext(ioDispatcher) {
            val entity = gatewayDao.getGatewayById(id) ?: return@withContext null
            toDomainOrCleanUp(entity)
        }
    }

    override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? {
        return withContext(ioDispatcher) {
            val entity = gatewayDao.getGatewayByGatewayId(gatewayId) ?: return@withContext null
            toDomainOrCleanUp(entity)
        }
    }

    private suspend fun toDomainOrCleanUp(entity: GatewaySourceEntity): GatewaySource? {
        val credKey = CredentialEncryptor.gatewayCredentialKey(entity.id)
        return try {
            GatewaySource(
                id = entity.id,
                name = entity.name,
                scheme = entity.scheme,
                host = entity.host,
                gatewayCredential = credentialEncryptor.decrypt(entity.gatewayCredential, credKey),
                gatewayCredentialExpiresAt = entity.gatewayCredentialExpiresAt,
                gatewayRemoteMode = entity.gatewayRemoteMode,
                gatewayId = entity.gatewayId,
            )
        } catch (_: CredentialEncryptor.CredentialUnavailableException) {
            scope.launch {
                gatewayDao.deleteGatewayById(entity.id)
                credentialEncryptor.delete(credKey)
            }
            null
        }
    }

    private suspend fun GatewaySource.toEntity(): GatewaySourceEntity {
        val credKey = CredentialEncryptor.gatewayCredentialKey(id)
        return GatewaySourceEntity(
            id = id,
            name = name,
            scheme = scheme,
            host = host,
            gatewayCredential = credentialEncryptor.encrypt(gatewayCredential, credKey),
            gatewayCredentialExpiresAt = gatewayCredentialExpiresAt,
            gatewayRemoteMode = gatewayRemoteMode,
            gatewayId = gatewayId,
        )
    }
}
