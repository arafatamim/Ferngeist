package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class LaunchableTargetRepositoryImpl(
    private val serverRepository: ServerRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val gatewayAgentBindingRepository: GatewayAgentBindingRepository,
) : LaunchableTargetRepository {
    override fun getTargets(): Flow<List<LaunchableTarget>> {
        return combine(
            serverRepository.getServers(),
            gatewaySourceRepository.getGateways(),
            gatewayAgentBindingRepository.getBindings(),
        ) { servers, gateways, bindings ->
            val gatewaysById = gateways.associateBy { it.id }
            buildList {
                servers.forEach { server ->
                    add(LaunchableTarget.Manual(server))
                }
                bindings.forEach { binding ->
                    val gateway = gatewaysById[binding.gatewaySourceId] ?: return@forEach
                    add(LaunchableTarget.GatewayAgent(binding, gateway))
                }
            }.sortedBy { it.name.lowercase() }
        }
    }

    override suspend fun getTarget(id: String): LaunchableTarget? {
        serverRepository.getServer(id)?.let { server ->
            return LaunchableTarget.Manual(server)
        }
        val binding = gatewayAgentBindingRepository.getBinding(id) ?: return null
        val gateway = gatewaySourceRepository.getGateway(binding.gatewaySourceId) ?: return null
        return LaunchableTarget.GatewayAgent(binding, gateway)
    }

    override suspend fun updatePreferredAuthMethod(
        targetId: String,
        methodId: String,
    ) {
        serverRepository.getServer(targetId)?.let { server ->
            if (server.preferredAuthMethodId != methodId) {
                serverRepository.updateServer(server.copy(preferredAuthMethodId = methodId))
            }
            return
        }
        gatewayAgentBindingRepository.getBinding(targetId)?.let { binding ->
            if (binding.preferredAuthMethodId != methodId) {
                gatewayAgentBindingRepository.updateBinding(binding.copy(preferredAuthMethodId = methodId))
            }
        }
    }

    override suspend fun deleteTarget(id: String) {
        serverRepository.getServer(id)?.let {
            serverRepository.deleteServer(id)
            return
        }
        gatewayAgentBindingRepository.deleteBinding(id)
    }
}
