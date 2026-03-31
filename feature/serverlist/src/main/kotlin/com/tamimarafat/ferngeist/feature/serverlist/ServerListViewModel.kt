package com.tamimarafat.ferngeist.feature.serverlist

import android.util.Log
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
import com.tamimarafat.ferngeist.core.model.ServerSourceKind
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

private data class DesktopHelperLaunchContext(
    val config: AcpConnectionConfig,
    val helperSource: ServerConfig,
    val runtimeId: String,
)

private const val LOG_TAG = "ServerListViewModel"

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val connectionManager: AcpConnectionManager,
    private val helperRepository: DesktopHelperRepository,
) : ViewModel() {

    val servers: StateFlow<List<ServerConfig>> = serverRepository.getServers()
        .map { configs -> configs.filter { it.sourceKind == ServerSourceKind.MANUAL_ACP || it.isDesktopHelperAgent } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasDesktopCompanions: StateFlow<Boolean> = serverRepository.getServers()
        .map { configs -> configs.any { it.isDesktopCompanion } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

            val helperLaunch = if (server.sourceKind == ServerSourceKind.DESKTOP_HELPER) {
                buildDesktopHelperLaunchContext(server)
            } else {
                Result.success<DesktopHelperLaunchContext?>(null)
            }

            val launchContext = helperLaunch.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        connectionState = AcpConnectionState.Failed(error),
                        connectedServerState = null,
                        showConnectionError = error.message ?: "Failed to launch ${server.name}",
                    )
                }
                return@launch
            }

            val resolvedConfig = launchContext?.config ?: AcpConnectionConfig(
                scheme = server.scheme,
                host = server.host,
                preferredAuthMethodId = server.preferredAuthMethodId,
            )

            val connected = withContext(Dispatchers.IO) {
                connectionManager.connect(resolvedConfig)
            }
            if (!connected) {
                val connectMessage = connectionManager.diagnostics.value.recentErrors
                    .lastOrNull { entry -> entry.source == "connect" || entry.source == "connection" }
                    ?.message
                    ?: "Failed to connect to ${server.name}"
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        connectionState = AcpConnectionState.Failed(Exception(connectMessage)),
                        connectedServerState = null,
                        showConnectionError = connectMessage,
                    )
                }
                return@launch
            }

            // Step 2: Initialize and get agent info
            val initializeResult = withContext(Dispatchers.IO) {
                connectionManager.initialize()
            }
            if (initializeResult == null) {
                val initializeDetail = buildInitializeFailureMessage(
                    server = server,
                    helperSource = launchContext?.helperSource,
                    runtimeId = launchContext?.runtimeId,
                )
                logConnectionFailure(server, "initialize", initializeDetail)
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        showConnectionError = shortInitializeFailureMessage(server),
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
                    openConnectedServer(server.id)
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

            when (val result = withContext(Dispatchers.IO) {
                connectionManager.authenticate(methodId)
            }) {
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

            val server = withContext(Dispatchers.IO) {
                serverRepository.getServer(serverId)
            }
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
            openConnectedServer(serverId)
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
            withContext(Dispatchers.IO) {
                serverRepository.deleteServer(serverId)
            }
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
        withContext(Dispatchers.IO) {
            serverRepository.updateServer(server.copy(preferredAuthMethodId = methodId))
        }
    }

    /**
     * Desktop helper agents need a two-stage launch: start or reuse the helper
     * runtime, then request a runtime-scoped ACP WebSocket handoff.
     */
    private suspend fun buildDesktopHelperLaunchContext(server: ServerConfig): Result<DesktopHelperLaunchContext> {
        val agentId = server.selectedAgentId
            ?: return Result.failure(IllegalStateException("Desktop helper agent is missing an agent id"))
        val helperSource = resolveDesktopHelperSource(server)
            ?: return Result.failure(IllegalStateException("Desktop companion not found for ${server.name}"))
        if (helperSource.helperCredential.isBlank()) {
            return Result.failure(IllegalStateException("Desktop companion is not paired"))
        }

        return runCatching {
            val runtime = withContext(Dispatchers.IO) {
                helperRepository.startAgent(
                    scheme = helperSource.scheme,
                    host = helperSource.host,
                    helperCredential = helperSource.helperCredential,
                    agentId = agentId,
                )
            }
            val handoff = withContext(Dispatchers.IO) {
                helperRepository.connectRuntime(
                    scheme = helperSource.scheme,
                    host = helperSource.host,
                    helperCredential = helperSource.helperCredential,
                    runtimeId = runtime.id,
                )
            }
            DesktopHelperLaunchContext(
                config = AcpConnectionConfig(
                    scheme = server.scheme,
                    host = server.host,
                    webSocketUrl = resolveDesktopHelperWebSocketUrl(helperSource, handoff),
                    preferredAuthMethodId = server.preferredAuthMethodId,
                ),
                helperSource = helperSource,
                runtimeId = runtime.id,
            )
        }
    }

    /**
     * Helper-backed initialize failures can happen after the helper has already
     * successfully started and handed off the runtime. Surface the recorded ACP
     * initialization error and, when available, the last helper runtime stderr
     * or ACP stdout line so the user sees the real agent failure instead of the
     * generic session initialization message.
     */
    private suspend fun buildInitializeFailureMessage(
        server: ServerConfig,
        helperSource: ServerConfig?,
        runtimeId: String?,
    ): String {
        val diagnosticMessage = connectionManager.diagnostics.value.recentErrors
            .lastOrNull { entry -> entry.source == "initialize" || entry.source == "connection" }
            ?.message
            ?.takeIf { it.isNotBlank() }
            ?: "Failed to initialize session with ${server.name}"

        if (helperSource == null || runtimeId.isNullOrBlank() || helperSource.helperCredential.isBlank()) {
            return diagnosticMessage
        }

        val runtimeHint = runCatching {
            helperRepository.fetchRuntimeLogs(
                scheme = helperSource.scheme,
                host = helperSource.host,
                helperCredential = helperSource.helperCredential,
                runtimeId = runtimeId,
            )
        }.getOrNull()
            ?.asReversed()
            ?.firstNotNullOfOrNull { entry ->
                entry.message.trim().takeIf {
                    it.isNotEmpty() && (entry.stream == "stderr" || entry.stream == "acp.stdout")
                }
            }

        if (runtimeHint.isNullOrBlank() || diagnosticMessage.contains(runtimeHint, ignoreCase = true)) {
            return diagnosticMessage
        }
        return "$diagnosticMessage\nHelper runtime: $runtimeHint"
    }

    private fun shortInitializeFailureMessage(server: ServerConfig): String {
        return "Failed to initialize session with ${server.name}. See logcat for details."
    }

    private fun logConnectionFailure(server: ServerConfig, phase: String, message: String) {
        runCatching {
            Log.e(LOG_TAG, "${server.name} $phase failed\n$message")
        }
    }

    /**
     * Helper handoff URLs are convenient, but some helper deployments advertise
     * wildcard or loopback hosts that are not reachable from Android. When that
     * happens, rebuild the socket URL from the paired helper host plus the
     * runtime-specific path and bearer token returned by the helper.
     */
    private fun resolveDesktopHelperWebSocketUrl(
        helperSource: ServerConfig,
        handoff: com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperConnectResponse,
    ): String {
        val advertisedUrl = handoff.webSocketUrl.trim()
        val advertisedHost = runCatching { URI(advertisedUrl).host?.lowercase() }.getOrNull()
        if (advertisedHost != null && !isUnroutableHelperHost(advertisedHost)) {
            return advertisedUrl
        }

        val socketScheme = when (helperSource.scheme.lowercase()) {
            "https", "wss" -> "wss"
            else -> "ws"
        }
        val encodedToken = URLEncoder.encode(handoff.bearerToken, StandardCharsets.UTF_8.toString())
        return "$socketScheme://${helperSource.host}${handoff.webSocketPath}?access_token=$encodedToken"
    }

    private fun isUnroutableHelperHost(host: String): Boolean {
        return host == "0.0.0.0" || host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    private suspend fun resolveDesktopHelperSource(server: ServerConfig): ServerConfig? {
        val helperSourceId = server.helperSourceId ?: return server.takeIf { it.isDesktopCompanion }
        return withContext(Dispatchers.IO) {
            serverRepository.getServer(helperSourceId)
        }
    }

    private suspend fun openConnectedServer(serverId: String) {
        _events.emit(
            ServerListEvent.NavigateToSessions(
                serverId = serverId,
                openCreateSessionDialog = false,
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
