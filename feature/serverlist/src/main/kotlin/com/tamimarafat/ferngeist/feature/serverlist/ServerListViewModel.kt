package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for a connected server, holding agent info and sessions.
 */
data class ConnectedServerState(
    val serverId: String,
    val agentName: String = "Agent",
    val agentDescription: String = "",
    val capabilities: List<String> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val isInitializing: Boolean = false,
)

/**
 * Full UI state for the server list screen.
 */
data class ServerListUiState(
    val connectionState: AcpConnectionState = AcpConnectionState.Disconnected,
    val connectingServerId: String? = null,
    val connectedServerState: ConnectedServerState? = null,
    val showConnectionError: String? = null,
    val pendingAuthentication: PendingAuthentication? = null,
)

data class PendingAuthentication(
    val serverId: String,
    val serverName: String,
    val agentName: String,
    val authMethods: List<AcpAuthMethodInfo>,
    val authErrorMessage: String? = null,
)

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val connectionManager: AcpConnectionManager,
) : ViewModel() {

    val servers: StateFlow<List<ServerConfig>> = serverRepository.getServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(ServerListUiState())
    val uiState: StateFlow<ServerListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ServerListEvent>()
    val events = _events.asSharedFlow()

    init {
        // Observe connection manager state changes
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        // Observe ACP manager events
        viewModelScope.launch {
            connectionManager.events.collect { event ->
                handleManagerEvent(event)
            }
        }
    }

    /**
     * Full connection orchestration flow:
     * connect → initialize → navigate
     */
    fun connectAndOpenServer(server: ServerConfig) {
        viewModelScope.launch {
            // Update UI state
            _uiState.update {
                it.copy(
                    connectingServerId = server.id,
                    connectionState = AcpConnectionState.Connecting,
                    showConnectionError = null,
                    pendingAuthentication = null,
                    connectedServerState = ConnectedServerState(
                        serverId = server.id,
                        isInitializing = true,
                    ),
                )
            }

            // Step 1: Connect
            val config = AcpConnectionConfig(
                scheme = server.scheme,
                host = server.host,
                preferredAuthMethodId = server.preferredAuthMethodId,
            )

            val connected = connectionManager.connect(config)
            if (!connected) {
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        connectionState = AcpConnectionState.Failed(Exception("Connection failed")),
                        connectedServerState = null,
                        showConnectionError = "Failed to connect to ${server.name}",
                    )
                }
                return@launch
            }

            // Step 2: Initialize and get agent info
            val initializeResult = connectionManager.initialize()
            if (initializeResult == null) {
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        showConnectionError = "Failed to initialize session with ${server.name}",
                        connectedServerState = null,
                    )
                }
                return@launch
            }

            when (initializeResult) {
                is AcpInitializeResult.Ready -> {
                    initializeResult.authenticatedMethodId?.let { authenticatedMethodId ->
                        savePreferredAuthMethod(server, authenticatedMethodId)
                    }
                    val capabilityLabels = initializeResult.agentCapabilities.displayLabels()
                    _uiState.update {
                        it.copy(
                            connectingServerId = null,
                            pendingAuthentication = null,
                            connectedServerState = it.connectedServerState?.copy(
                                agentName = initializeResult.agentInfo.name,
                                capabilities = capabilityLabels,
                                isInitializing = false,
                            ),
                        )
                    }
                    openConnectedServer(server.id, initializeResult.agentCapabilities.session.list)
                }

                is AcpInitializeResult.AuthenticationRequired -> {
                    val capabilityLabels = initializeResult.agentCapabilities.displayLabels()
                    _uiState.update {
                        it.copy(
                            connectingServerId = null,
                            pendingAuthentication = PendingAuthentication(
                                serverId = server.id,
                                serverName = server.name,
                                agentName = initializeResult.agentInfo.name,
                                authMethods = initializeResult.authMethods,
                                authErrorMessage = initializeResult.authErrorMessage,
                            ),
                            connectedServerState = it.connectedServerState?.copy(
                                agentName = initializeResult.agentInfo.name,
                                capabilities = capabilityLabels,
                                isInitializing = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun authenticate(serverId: String, methodId: String) {
        viewModelScope.launch {
            val pending = _uiState.value.pendingAuthentication
            if (pending == null || pending.serverId != serverId) return@launch

            _uiState.update {
                it.copy(
                    connectingServerId = serverId,
                    showConnectionError = null,
                    pendingAuthentication = pending.copy(authErrorMessage = null),
                )
            }

            when (val result = connectionManager.authenticate(methodId)) {
                is AcpAuthenticateResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            connectingServerId = null,
                            pendingAuthentication = pending.copy(
                                authErrorMessage = result.message,
                            ),
                        )
                    }
                    return@launch
                }

                AcpAuthenticateResult.Success -> Unit
            }

            val server = serverRepository.getServer(serverId)
            if (server != null) {
                savePreferredAuthMethod(server, methodId)
            }

            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = null,
                    connectedServerState = it.connectedServerState?.copy(isInitializing = false),
                )
            }
            openConnectedServer(serverId, connectionManager.agentCapabilities.value?.session?.list == true)
        }
    }

    fun dismissAuthenticationPrompt() {
        _uiState.update { it.copy(pendingAuthentication = null, connectingServerId = null) }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectionManager.disconnect()
            _uiState.update {
                it.copy(
                    connectionState = AcpConnectionState.Disconnected,
                    connectingServerId = null,
                    connectedServerState = null,
                )
            }
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            serverRepository.deleteServer(serverId)
            // If we were connected to this server, disconnect
            if (_uiState.value.connectedServerState?.serverId == serverId) {
                disconnect()
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(showConnectionError = null) }
    }

    private fun handleManagerEvent(event: AcpManagerEvent) {
        when (event) {
            is AcpManagerEvent.Initialized -> {
                val capabilityLabels = event.result.agentCapabilities.displayLabels()
                _uiState.update { current ->
                    current.copy(
                        connectedServerState = current.connectedServerState?.copy(
                            agentName = event.result.agentInfo.name,
                            isInitializing = false,
                        ),
                    )
                }
            }
            is AcpManagerEvent.Authenticated -> Unit
            is AcpManagerEvent.Error -> {
                val message = formatAcpErrorMessage(event.throwable, "Unknown error")
                _uiState.update { current ->
                    val pendingAuthentication = current.pendingAuthentication
                    if (pendingAuthentication != null && current.connectingServerId == pendingAuthentication.serverId) {
                        current.copy(
                            connectingServerId = null,
                            pendingAuthentication = pendingAuthentication.copy(authErrorMessage = message),
                            connectedServerState = current.connectedServerState?.copy(isInitializing = false),
                        )
                    } else {
                        current.copy(
                            showConnectionError = message,
                            connectedServerState = current.connectedServerState?.copy(
                                isInitializing = false,
                            ),
                        )
                    }
                }
            }
            is AcpManagerEvent.Connected -> { /* Handled by connection state */ }
            is AcpManagerEvent.Disconnected -> { /* Handled by connection state */ }
        }
    }

    private suspend fun savePreferredAuthMethod(server: ServerConfig, methodId: String) {
        if (server.preferredAuthMethodId == methodId) return
        serverRepository.updateServer(server.copy(preferredAuthMethodId = methodId))
    }

    private suspend fun openConnectedServer(serverId: String, supportsSessionList: Boolean) {
        _events.emit(
            ServerListEvent.NavigateToSessions(
                serverId = serverId,
                openCreateSessionDialog = !supportsSessionList,
            )
        )
    }
}

sealed interface ServerListEvent {
    data class NavigateToSessions(
        val serverId: String,
        val sessions: List<SessionSummary> = emptyList(),
        val openCreateSessionDialog: Boolean = false,
    ) : ServerListEvent
    data class ShowError(val message: String) : ServerListEvent
}
