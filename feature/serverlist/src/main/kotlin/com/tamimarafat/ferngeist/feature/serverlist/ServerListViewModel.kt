package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
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
                authToken = server.token.takeIf { it.isNotBlank() },
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
            val initialized = connectionManager.initialize()
            if (!initialized) {
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        showConnectionError = "Failed to initialize session with ${server.name}",
                        connectedServerState = null,
                    )
                }
                return@launch
            }

            // Step 3: Update connected state and navigate
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    connectedServerState = it.connectedServerState?.copy(
                        isInitializing = false,
                    ),
                )
            }

            // Navigate to sessions
            _events.emit(ServerListEvent.NavigateToSessions(server.id))
        }
    }

    /**
     * Quick select without connecting — just navigate to sessions.
     * Useful if already connected.
     */
    fun selectServer(serverId: String) {
        viewModelScope.launch {
            _events.emit(ServerListEvent.NavigateToSessions(serverId))
        }
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
                _uiState.update { current ->
                    current.copy(
                        connectedServerState = current.connectedServerState?.copy(
                            agentName = event.agentInfo.name,
                            isInitializing = false,
                        ),
                    )
                }
            }
            is AcpManagerEvent.Error -> {
                _uiState.update {
                    it.copy(
                        showConnectionError = event.throwable.message ?: "Unknown error",
                        connectedServerState = it.connectedServerState?.copy(
                            isInitializing = false,
                        ),
                    )
                }
            }
            is AcpManagerEvent.Connected -> { /* Handled by connection state */ }
            is AcpManagerEvent.Disconnected -> { /* Handled by connection state */ }
        }
    }
}

    sealed interface ServerListEvent {
        data class NavigateToSessions(val serverId: String, val sessions: List<SessionSummary> = emptyList()) : ServerListEvent
        data class ShowError(val message: String) : ServerListEvent
    }
