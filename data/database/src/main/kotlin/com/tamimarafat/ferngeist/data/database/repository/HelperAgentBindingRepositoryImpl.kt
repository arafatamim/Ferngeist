package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.HelperAgentBinding
import com.tamimarafat.ferngeist.core.model.repository.HelperAgentBindingRepository
import com.tamimarafat.ferngeist.data.database.dao.HelperAgentBindingDao
import com.tamimarafat.ferngeist.data.database.entity.HelperAgentBindingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HelperAgentBindingRepositoryImpl(
    private val bindingDao: HelperAgentBindingDao,
) : HelperAgentBindingRepository {

    override fun getBindings(): Flow<List<HelperAgentBinding>> {
        return bindingDao.getAllBindings().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun addBinding(binding: HelperAgentBinding) {
        bindingDao.insertBinding(binding.toEntity())
    }

    override suspend fun updateBinding(binding: HelperAgentBinding) {
        bindingDao.updateBinding(binding.toEntity())
    }

    override suspend fun deleteBinding(id: String) {
        bindingDao.deleteBindingById(id)
    }

    override suspend fun getBinding(id: String): HelperAgentBinding? {
        return bindingDao.getBindingById(id)?.toDomain()
    }

    override suspend fun getBindingsForHelper(helperId: String): List<HelperAgentBinding> {
        return bindingDao.getBindingsForHelper(helperId).map { it.toDomain() }
    }
}

private fun HelperAgentBindingEntity.toDomain(): HelperAgentBinding {
    return HelperAgentBinding(
        id = id,
        name = name,
        helperSourceId = helperSourceId,
        agentId = agentId,
        workingDirectory = workingDirectory,
        preferredAuthMethodId = preferredAuthMethodId,
    )
}

private fun HelperAgentBinding.toEntity(): HelperAgentBindingEntity {
    return HelperAgentBindingEntity(
        id = id,
        name = name,
        helperSourceId = helperSourceId,
        agentId = agentId,
        workingDirectory = workingDirectory,
        preferredAuthMethodId = preferredAuthMethodId,
    )
}
