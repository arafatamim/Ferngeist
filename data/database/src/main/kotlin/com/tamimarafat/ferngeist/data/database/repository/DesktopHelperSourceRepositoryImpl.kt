package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.data.database.crypto.CredentialEncryptor
import com.tamimarafat.ferngeist.data.database.dao.DesktopHelperSourceDao
import com.tamimarafat.ferngeist.data.database.entity.DesktopHelperSourceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DesktopHelperSourceRepositoryImpl(
    private val helperDao: DesktopHelperSourceDao,
    private val credentialEncryptor: CredentialEncryptor,
) : DesktopHelperSourceRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getHelpers(): Flow<List<DesktopHelperSource>> {
        return helperDao.getAllHelpers().map { entities ->
            withContext(Dispatchers.IO) {
                entities.mapNotNull { entity ->
                    toDomainOrCleanUp(entity)
                }
            }
        }
    }

    override suspend fun addHelper(helper: DesktopHelperSource) {
        withContext(Dispatchers.IO) {
            helperDao.insertHelper(helper.toEntity())
        }
    }

    override suspend fun updateHelper(helper: DesktopHelperSource) {
        withContext(Dispatchers.IO) {
            helperDao.updateHelper(helper.toEntity())
        }
    }

    override suspend fun deleteHelper(id: String) {
        withContext(Dispatchers.IO) {
            helperDao.deleteHelperById(id)
            credentialEncryptor.delete(CredentialEncryptor.helperCredentialKey(id))
        }
    }

    override suspend fun getHelper(id: String): DesktopHelperSource? {
        return withContext(Dispatchers.IO) {
            val entity = helperDao.getHelperById(id) ?: return@withContext null
            toDomainOrCleanUp(entity)
        }
    }

    private fun toDomainOrCleanUp(entity: DesktopHelperSourceEntity): DesktopHelperSource? {
        val credKey = CredentialEncryptor.helperCredentialKey(entity.id)
        return try {
            DesktopHelperSource(
                id = entity.id,
                name = entity.name,
                scheme = entity.scheme,
                host = entity.host,
                helperCredential = credentialEncryptor.decrypt(entity.helperCredential, credKey),
                helperCredentialExpiresAt = entity.helperCredentialExpiresAt,
                helperRemoteMode = entity.helperRemoteMode,
            )
        } catch (_: CredentialEncryptor.CredentialUnavailableException) {
            scope.launch {
                helperDao.deleteHelperById(entity.id)
                credentialEncryptor.delete(credKey)
            }
            null
        }
    }

    private fun DesktopHelperSource.toEntity(): DesktopHelperSourceEntity {
        val credKey = CredentialEncryptor.helperCredentialKey(id)
        return DesktopHelperSourceEntity(
            id = id,
            name = name,
            scheme = scheme,
            host = host,
            helperCredential = credentialEncryptor.encrypt(helperCredential, credKey),
            helperCredentialExpiresAt = helperCredentialExpiresAt,
            helperRemoteMode = helperRemoteMode,
        )
    }
}
