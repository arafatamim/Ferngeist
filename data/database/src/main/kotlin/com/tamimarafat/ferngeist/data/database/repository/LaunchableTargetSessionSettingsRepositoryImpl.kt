package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.LaunchableTargetSessionSettings
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.data.database.dao.LaunchableTargetSessionSettingsDao
import com.tamimarafat.ferngeist.data.database.entity.LaunchableTargetSessionSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LaunchableTargetSessionSettingsRepositoryImpl(
    private val dao: LaunchableTargetSessionSettingsDao,
) : LaunchableTargetSessionSettingsRepository {
    override fun getSettings(targetId: String): Flow<LaunchableTargetSessionSettings> =
        dao.getSettingsByTargetId(targetId).map { entity ->
            entity?.toDomain() ?: LaunchableTargetSessionSettings(targetId = targetId)
        }

    override suspend fun getSettingsBlocking(targetId: String): LaunchableTargetSessionSettings? =
        dao.getSettingsByTargetIdBlocking(targetId)?.toDomain()

    override suspend fun updateCwd(
        targetId: String,
        cwd: String,
    ) {
        val normalizedCwd = cwd.trim().ifBlank { null }
        dao.upsertSettings(
            LaunchableTargetSessionSettingsEntity(
                targetId = targetId,
                cwd = normalizedCwd,
            ),
        )
    }

    override suspend fun deleteSettings(targetId: String) {
        dao.deleteSettingsByTargetId(targetId)
    }
}

private fun LaunchableTargetSessionSettingsEntity.toDomain(): LaunchableTargetSessionSettings =
    LaunchableTargetSessionSettings(
        targetId = targetId,
        cwd = cwd,
    )
