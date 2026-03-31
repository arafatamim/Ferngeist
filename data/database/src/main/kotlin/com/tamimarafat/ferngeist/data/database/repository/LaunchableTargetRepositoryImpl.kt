package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.core.model.repository.HelperAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class LaunchableTargetRepositoryImpl(
    private val serverRepository: ServerRepository,
    private val helperSourceRepository: DesktopHelperSourceRepository,
    private val helperAgentBindingRepository: HelperAgentBindingRepository,
) : LaunchableTargetRepository {

    override fun getTargets(): Flow<List<LaunchableTarget>> {
        return combine(
            serverRepository.getServers(),
            helperSourceRepository.getHelpers(),
            helperAgentBindingRepository.getBindings(),
        ) { servers, helpers, bindings ->
            val helpersById = helpers.associateBy { it.id }
            buildList {
                servers.forEach { server ->
                    add(LaunchableTarget.Manual(server))
                }
                bindings.forEach { binding ->
                    val helper = helpersById[binding.helperSourceId] ?: return@forEach
                    add(LaunchableTarget.HelperAgent(binding, helper))
                }
            }.sortedBy { it.name.lowercase() }
        }
    }

    override suspend fun getTarget(id: String): LaunchableTarget? {
        serverRepository.getServer(id)?.let { server ->
            return LaunchableTarget.Manual(server)
        }
        val binding = helperAgentBindingRepository.getBinding(id) ?: return null
        val helper = helperSourceRepository.getHelper(binding.helperSourceId) ?: return null
        return LaunchableTarget.HelperAgent(binding, helper)
    }

    override suspend fun updatePreferredAuthMethod(targetId: String, methodId: String) {
        serverRepository.getServer(targetId)?.let { server ->
            if (server.preferredAuthMethodId != methodId) {
                serverRepository.updateServer(server.copy(preferredAuthMethodId = methodId))
            }
            return
        }
        helperAgentBindingRepository.getBinding(targetId)?.let { binding ->
            if (binding.preferredAuthMethodId != methodId) {
                helperAgentBindingRepository.updateBinding(binding.copy(preferredAuthMethodId = methodId))
            }
        }
    }

    override suspend fun deleteTarget(id: String) {
        serverRepository.getServer(id)?.let {
            serverRepository.deleteServer(id)
            return
        }
        helperAgentBindingRepository.deleteBinding(id)
    }
}
