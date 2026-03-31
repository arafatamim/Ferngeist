package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.data.database.dao.DesktopHelperSourceDao
import com.tamimarafat.ferngeist.data.database.entity.DesktopHelperSourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DesktopHelperSourceRepositoryImpl(
    private val helperDao: DesktopHelperSourceDao,
) : DesktopHelperSourceRepository {

    override fun getHelpers(): Flow<List<DesktopHelperSource>> {
        return helperDao.getAllHelpers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun addHelper(helper: DesktopHelperSource) {
        helperDao.insertHelper(helper.toEntity())
    }

    override suspend fun updateHelper(helper: DesktopHelperSource) {
        helperDao.updateHelper(helper.toEntity())
    }

    override suspend fun deleteHelper(id: String) {
        helperDao.deleteHelperById(id)
    }

    override suspend fun getHelper(id: String): DesktopHelperSource? {
        return helperDao.getHelperById(id)?.toDomain()
    }
}

private fun DesktopHelperSourceEntity.toDomain(): DesktopHelperSource {
    return DesktopHelperSource(
        id = id,
        name = name,
        scheme = scheme,
        host = host,
        helperCredential = helperCredential,
        helperCredentialExpiresAt = helperCredentialExpiresAt,
        helperRemoteMode = helperRemoteMode,
    )
}

private fun DesktopHelperSource.toEntity(): DesktopHelperSourceEntity {
    return DesktopHelperSourceEntity(
        id = id,
        name = name,
        scheme = scheme,
        host = host,
        helperCredential = helperCredential,
        helperCredentialExpiresAt = helperCredentialExpiresAt,
        helperRemoteMode = helperRemoteMode,
    )
}
