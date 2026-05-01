package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GatewayAgentsUiState(
    val gateway: GatewaySource? = null,
    val agents: List<GatewayAgent> = emptyList(),
    val addedAgentIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
)

/**
 * Loads gateway-visible agents for one paired gateway and lets the
 * user promote selected agents into the main launchable agent list.
 */
@HiltViewModel
class GatewayAgentsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val gatewayAgentBindingRepository: GatewayAgentBindingRepository,
        private val gatewayRepository: GatewayRepository,
    ) : ViewModel() {
        private val gatewayId: String = savedStateHandle.get<String>("serverId").orEmpty()

        private val _uiState = MutableStateFlow(GatewayAgentsUiState())
        val uiState: StateFlow<GatewayAgentsUiState> = _uiState.asStateFlow()

        private val _events = MutableSharedFlow<String>()
        val events = _events.asSharedFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                val bindings = gatewayAgentBindingRepository.getBindingsForGateway(gatewayId)
                val storedGateway = gatewaySourceRepository.getGateway(gatewayId)
                if (storedGateway == null) {
                    _uiState.value =
                        GatewayAgentsUiState(
                            isLoading = false,
                            loadError = "Gateway not found",
                        )
                    return@launch
                }
                val gateway = refreshGatewaySourceIfNeeded(storedGateway, gatewayRepository, gatewaySourceRepository)

                _uiState.value =
                    _uiState.value.copy(
                        gateway = gateway,
                        isLoading = true,
                        loadError = null,
                    )
                runCatching {
                    gatewayRepository.fetchAgents(gateway.scheme, gateway.host, gateway.gatewayCredential)
                }.onSuccess { agents ->
                    _uiState.value =
                        GatewayAgentsUiState(
                            gateway = gateway,
                            agents = agents,
                            addedAgentIds =
                                bindings
                                    .map { it.agentId }
                                    .toSet(),
                            isLoading = false,
                            loadError = null,
                        )
                }.onFailure { error ->
                    _uiState.value =
                        _uiState.value.copy(
                            gateway = gateway,
                            isLoading = false,
                            loadError = "Could not connect to gateway: ${error.message ?: "unknown error"}",
                        )
                }
            }
        }

        fun addAgent(agent: GatewayAgent) {
            val state = _uiState.value
            val gateway = state.gateway ?: return
            if (agent.id in state.addedAgentIds) return

            viewModelScope.launch {
                val binding =
                    GatewayAgentBinding(
                        name = agent.displayName,
                        gatewaySourceId = gateway.id,
                        agentId = agent.id,
                    )
                gatewayAgentBindingRepository.addBinding(binding)
                _uiState.value = state.copy(addedAgentIds = state.addedAgentIds + agent.id)
                _events.emit("Added ${agent.displayName}")
            }
        }
    }
