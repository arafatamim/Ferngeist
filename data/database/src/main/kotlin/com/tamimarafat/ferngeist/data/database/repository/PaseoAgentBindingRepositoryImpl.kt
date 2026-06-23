package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.PaseoAgentBinding
import com.tamimarafat.ferngeist.core.model.repository.PaseoAgentBindingRepository
import com.tamimarafat.ferngeist.data.database.dao.PaseoAgentBindingDao
import com.tamimarafat.ferngeist.data.database.entity.PaseoAgentBindingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PaseoAgentBindingRepositoryImpl(
    private val bindingDao: PaseoAgentBindingDao,
) : PaseoAgentBindingRepository {
    override fun getBindings(): Flow<List<PaseoAgentBinding>> =
        bindingDao.getAllBindings().map { entities -> entities.map { it.toDomain() } }

    override suspend fun addBinding(binding: PaseoAgentBinding) {
        withContext(Dispatchers.IO) {
            bindingDao.insertBinding(binding.toEntity())
        }
    }

    override suspend fun updateBinding(binding: PaseoAgentBinding) {
        withContext(Dispatchers.IO) {
            bindingDao.updateBinding(binding.toEntity())
        }
    }

    override suspend fun deleteBinding(id: String) {
        withContext(Dispatchers.IO) {
            bindingDao.deleteBindingById(id)
        }
    }

    override suspend fun getBinding(id: String): PaseoAgentBinding? =
        withContext(Dispatchers.IO) {
            bindingDao.getBindingById(id)?.toDomain()
        }

    override suspend fun getBindingsForSource(sourceId: String): List<PaseoAgentBinding> =
        withContext(Dispatchers.IO) {
            bindingDao.getBindingsForSource(sourceId).map { it.toDomain() }
        }

    private fun PaseoAgentBindingEntity.toDomain(): PaseoAgentBinding =
        PaseoAgentBinding(
            id = id,
            name = name,
            paseoSourceId = paseoSourceId,
            provider = provider,
            cwd = cwd,
            preferredModelId = preferredModelId,
            preferredAuthMethodId = preferredAuthMethodId,
        )

    private fun PaseoAgentBinding.toEntity(): PaseoAgentBindingEntity =
        PaseoAgentBindingEntity(
            id = id,
            name = name,
            paseoSourceId = paseoSourceId,
            provider = provider,
            cwd = cwd,
            preferredModelId = preferredModelId,
            preferredAuthMethodId = preferredAuthMethodId,
        )
}
