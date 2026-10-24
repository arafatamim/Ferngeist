package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists paired gateways separately from launchable agents so the main
 * server list can stay focused on user-startable entries only.
 */
@HiltViewModel
class GatewayListViewModel
    @Inject
    constructor(
        private val repos: ServerRepositories,
        private val stores: ServerLocalStores,
        private val gatewayAgentBindingRepository: GatewayAgentBindingRepository,
    ) : ViewModel() {
        val gateways: StateFlow<List<GatewaySource>> =
            repos.gatewaySourceRepository
                .getGateways()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun deleteGateway(gateway: GatewaySource) {
            viewModelScope.launch {
                gatewayAgentBindingRepository.getBindingsForGateway(gateway.id).forEach { binding ->
                    stores.authEnvValueStore.deleteValues(binding.id)
                    repos.sessionRepository.clearSessions(binding.id)
                    repos.sessionSettingsRepository.deleteSettings(binding.id)
                    stores.recentCwdStore.clear(binding.id)
                    // Prefix-based cleanup: clearByPrefix removes all entries whose DataStore key
                    // starts with the given prefix. Trailing ":" prevents cross-server ID matches.
                    stores.recentSelectionStore.clearByPrefix("config_option:${binding.id}:")
                    stores.recentSelectionStore.clearByPrefix("commands:${binding.id}:")
                    gatewayAgentBindingRepository.deleteBinding(binding.id)
                }
                stores.agentLaunchConsentStore.clearByPrefix(gateway.id)
                stores.authEnvValueStore.deleteValues(gateway.id)
                repos.gatewaySourceRepository.deleteGateway(gateway.id)
            }
        }
    }
