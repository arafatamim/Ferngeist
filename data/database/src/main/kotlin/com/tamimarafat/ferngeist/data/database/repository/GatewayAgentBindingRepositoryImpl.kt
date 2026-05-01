package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.data.database.dao.GatewayAgentBindingDao
import com.tamimarafat.ferngeist.data.database.entity.GatewayAgentBindingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GatewayAgentBindingRepositoryImpl(
    private val bindingDao: GatewayAgentBindingDao,
) : GatewayAgentBindingRepository {
    override fun getBindings(): Flow<List<GatewayAgentBinding>> =
        bindingDao.getAllBindings().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun addBinding(binding: GatewayAgentBinding) {
        bindingDao.insertBinding(binding.toEntity())
    }

    override suspend fun updateBinding(binding: GatewayAgentBinding) {
        bindingDao.updateBinding(binding.toEntity())
    }

    override suspend fun deleteBinding(id: String) {
        bindingDao.deleteBindingById(id)
    }

    override suspend fun getBinding(id: String): GatewayAgentBinding? = bindingDao.getBindingById(id)?.toDomain()

    override suspend fun getBindingsForGateway(gatewayId: String): List<GatewayAgentBinding> =
        bindingDao.getBindingsForGateway(gatewayId).map {
            it.toDomain()
        }
}

private fun GatewayAgentBindingEntity.toDomain(): GatewayAgentBinding =
    GatewayAgentBinding(
        id = id,
        name = name,
        gatewaySourceId = gatewaySourceId,
        agentId = agentId,
        preferredAuthMethodId = preferredAuthMethodId,
    )

private fun GatewayAgentBinding.toEntity(): GatewayAgentBindingEntity =
    GatewayAgentBindingEntity(
        id = id,
        name = name,
        gatewaySourceId = gatewaySourceId,
        agentId = agentId,
        preferredAuthMethodId = preferredAuthMethodId,
    )
