package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.PaseoSource
import com.tamimarafat.ferngeist.core.model.repository.PaseoSourceRepository
import com.tamimarafat.ferngeist.data.database.crypto.CredentialEncryptor
import com.tamimarafat.ferngeist.data.database.dao.PaseoSourceDao
import com.tamimarafat.ferngeist.data.database.entity.PaseoSourceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaseoSourceRepositoryImpl(
    private val sourceDao: PaseoSourceDao,
    private val credentialEncryptor: CredentialEncryptor,
) : PaseoSourceRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getSources(): Flow<List<PaseoSource>> =
        sourceDao.getAllSources().map { entities ->
            withContext(Dispatchers.IO) {
                entities.mapNotNull { entity -> toDomainOrCleanUp(entity) }
            }
        }

    override suspend fun addSource(source: PaseoSource) {
        withContext(Dispatchers.IO) {
            sourceDao.insertSource(source.toEntity())
        }
    }

    override suspend fun updateSource(source: PaseoSource) {
        withContext(Dispatchers.IO) {
            sourceDao.updateSource(source.toEntity())
        }
    }

    override suspend fun deleteSource(id: String) {
        withContext(Dispatchers.IO) {
            sourceDao.deleteSourceById(id)
            credentialEncryptor.delete(CredentialEncryptor.paseoPasswordKey(id))
        }
    }

    override suspend fun getSource(id: String): PaseoSource? {
        return withContext(Dispatchers.IO) {
            val entity = sourceDao.getSourceById(id) ?: return@withContext null
            toDomainOrCleanUp(entity)
        }
    }

    private suspend fun toDomainOrCleanUp(entity: PaseoSourceEntity): PaseoSource? {
        val key = CredentialEncryptor.paseoPasswordKey(entity.id)
        return try {
            PaseoSource(
                id = entity.id,
                name = entity.name,
                mode = entity.mode,
                scheme = entity.scheme,
                host = entity.host,
                // Open daemons store an empty password as plaintext; decrypt() returns it
                // unchanged because it lacks the encrypted prefix.
                password = credentialEncryptor.decrypt(entity.password, key),
                serverId = entity.serverId,
                daemonPublicKeyB64 = entity.daemonPublicKeyB64,
                relayEndpoint = entity.relayEndpoint,
                relayUseTls = entity.relayUseTls,
            )
        } catch (_: CredentialEncryptor.CredentialUnavailableException) {
            scope.launch {
                sourceDao.deleteSourceById(entity.id)
                credentialEncryptor.delete(key)
            }
            null
        }
    }

    private suspend fun PaseoSource.toEntity(): PaseoSourceEntity {
        val key = CredentialEncryptor.paseoPasswordKey(id)
        val storedPassword =
            if (password.isBlank()) "" else credentialEncryptor.encrypt(password, key)
        return PaseoSourceEntity(
            id = id,
            name = name,
            mode = mode,
            scheme = scheme,
            host = host,
            password = storedPassword,
            serverId = serverId,
            daemonPublicKeyB64 = daemonPublicKeyB64,
            relayEndpoint = relayEndpoint,
            relayUseTls = relayUseTls,
        )
    }
}
