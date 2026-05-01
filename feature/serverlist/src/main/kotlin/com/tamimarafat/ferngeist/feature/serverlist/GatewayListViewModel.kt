package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import com.tamimarafat.ferngeist.feature.serverlist.consent.AgentLaunchConsentStore
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
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val gatewayAgentBindingRepository: GatewayAgentBindingRepository,
        private val sessionRepository: SessionRepository,
        private val authEnvValueStore: AuthEnvValueStore,
        private val agentLaunchConsentStore: AgentLaunchConsentStore,
        private val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
    ) : ViewModel() {
        val gateways: StateFlow<List<GatewaySource>> =
            gatewaySourceRepository
                .getGateways()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun deleteGateway(gateway: GatewaySource) {
            viewModelScope.launch {
                gatewayAgentBindingRepository.getBindingsForGateway(gateway.id).forEach { binding ->
                    authEnvValueStore.deleteValues(binding.id)
                    sessionRepository.clearSessions(binding.id)
                    sessionSettingsRepository.deleteSettings(binding.id)
                    gatewayAgentBindingRepository.deleteBinding(binding.id)
                }
                agentLaunchConsentStore.clearByPrefix(gateway.id)
                authEnvValueStore.deleteValues(gateway.id)
                gatewaySourceRepository.deleteGateway(gateway.id)
            }
        }
    }
