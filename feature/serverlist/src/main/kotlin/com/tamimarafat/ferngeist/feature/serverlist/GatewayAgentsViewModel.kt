package com.tamimarafat.ferngeist.feature.serverlist

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** HTTP 404 Not Found: the runtime exited before the stop request reached the gateway. */
private const val AGENT_NOT_FOUND_STATUS = 404

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
        private val application: Application,
        savedStateHandle: SavedStateHandle,
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val gatewayAgentBindingRepository: GatewayAgentBindingRepository,
        private val gatewayRepository: GatewayRepository,
    ) : ViewModel() {
        val gatewayId: String = savedStateHandle.get<String>("serverId").orEmpty()

        private val resources get() = application.resources

        private val _uiState = MutableStateFlow(GatewayAgentsUiState())
        val uiState: StateFlow<GatewayAgentsUiState> = _uiState.asStateFlow()

        private val _events = MutableSharedFlow<String>()
        val events = _events.asSharedFlow()

        private val _pendingDelete = MutableStateFlow<CustomAgentDelete?>(null)
        val pendingDelete: StateFlow<CustomAgentDelete?> = _pendingDelete.asStateFlow()

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
                val gateway =
                    try {
                        refreshGatewaySourceIfNeeded(storedGateway, gatewayRepository, gatewaySourceRepository)
                    } catch (_: GatewayCredentialExpiredException) {
                        gatewaySourceRepository.deleteGateway(gatewayId)
                        _uiState.value =
                            GatewayAgentsUiState(
                                isLoading = false,
                                loadError = "Gateway credential expired. Please pair this gateway again.",
                            )
                        return@launch
                    }

                _uiState.value =
                    _uiState.value.copy(
                        gateway = gateway,
                        isLoading = true,
                        loadError = null,
                    )
                runCatching {
                    // Verify protocol compatibility before touching the agent API.
                    gatewayRepository.fetchStatus(gateway.scheme, gateway.host)
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

        fun requestDelete(agent: GatewayAgent) {
            _pendingDelete.value = CustomAgentDelete.Confirm(agent)
        }

        fun dismissDelete() {
            _pendingDelete.value = null
        }

        /**
         * Deletes the agent. [stopFirst] is set when the user accepted the stop offer
         * after a refused delete, so the second attempt stops the runtime first.
         */
        @Suppress("TooGenericExceptionCaught")
        fun confirmDelete(
            agent: GatewayAgent,
            stopFirst: Boolean,
        ) {
            _pendingDelete.value = null
            val storedGateway = _uiState.value.gateway ?: return
            viewModelScope.launch {
                // Renew first: a credential that died since the list loaded would otherwise
                // surface as a generic delete error and stay stored.
                val gateway =
                    try {
                        refreshGatewaySourceIfNeeded(storedGateway, gatewayRepository, gatewaySourceRepository)
                    } catch (_: GatewayCredentialExpiredException) {
                        gatewaySourceRepository.deleteGateway(storedGateway.id)
                        _events.emit(
                            resources.getString(R.string.serverlist_custom_agent_error_credential_expired),
                        )
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A transport fault while renewing is still a failed delete, not a crash.
                        _events.emit(resources.getString(R.string.serverlist_custom_agent_error_delete_failed))
                        return@launch
                    }
                try {
                    if (stopFirst) stopRuntimes(agent, gateway)
                    gatewayRepository.deleteCustomAgent(
                        scheme = gateway.scheme,
                        host = gateway.host,
                        gatewayCredential = gateway.gatewayCredential,
                        agentId = agent.id,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!stopFirst && isAgentRunningError(error)) {
                        _pendingDelete.value = CustomAgentDelete.Running(agent)
                    } else {
                        _events.emit(resources.getString(R.string.serverlist_custom_agent_error_delete_failed))
                    }
                    return@launch
                }
                // The gateway delete succeeded, so local bookkeeping must never turn success
                // into a reported failure — or, worse, crash the process.
                removeBindingsQuietly(agent.id, gateway.id)
                _events.emit(resources.getString(R.string.serverlist_custom_agent_deleted, agent.displayName))
                refresh()
            }
        }

        /**
         * A 404 here means the runtime exited between the refused delete and this call:
         * there is nothing left to stop, so the delete decides the outcome.
         */
        private suspend fun stopRuntimes(
            agent: GatewayAgent,
            gateway: GatewaySource,
        ) {
            try {
                gatewayRepository.stopAgent(
                    scheme = gateway.scheme,
                    host = gateway.host,
                    gatewayCredential = gateway.gatewayCredential,
                    agentId = agent.id,
                )
            } catch (error: GatewayRequestException) {
                if (error.statusCode != AGENT_NOT_FOUND_STATUS) throw error
            }
        }

        /**
         * Best-effort binding cleanup: the gateway delete already succeeded, so a local
         * failure must not be reported as a delete failure, nor escape and crash.
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun removeBindingsQuietly(
            agentId: String,
            gatewayId: String,
        ) {
            try {
                removeBindings(agentId, gatewayId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A stale local binding is recoverable: the agent is gone either way.
            }
        }

        private suspend fun removeBindings(
            agentId: String,
            gatewayId: String,
        ) {
            gatewayAgentBindingRepository
                .getBindingsForGateway(gatewayId)
                .filter { it.agentId == agentId }
                .forEach { gatewayAgentBindingRepository.deleteBinding(it.id) }
        }
    }
