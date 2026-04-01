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
import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
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
    val persistedEnvValues: Map<String, String> = emptyMap(),
    val authErrorMessage: String? = null,
    val helperRuntime: PendingHelperRuntime? = null,
)

data class PendingHelperRuntime(
    val runtimeId: String,
)

private data class DesktopHelperLaunchContext(
    val config: AcpConnectionConfig,
    val helperSource: DesktopHelperSource,
    val runtimeId: String,
)

private const val LOG_TAG = "ServerListViewModel"

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val helperSourceRepository: DesktopHelperSourceRepository,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val sessionRepository: SessionRepository,
    private val connectionManager: AcpConnectionManager,
    private val helperRepository: DesktopHelperRepository,
    private val authEnvValueStore: AuthEnvValueStore,
    private val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
) : ViewModel() {

    val servers: StateFlow<List<LaunchableTarget>> = launchableTargetRepository.getTargets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasDesktopCompanions: StateFlow<Boolean> = helperSourceRepository.getHelpers()
        .map { helpers -> helpers.isNotEmpty() }
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
    fun connectAndOpenServer(server: LaunchableTarget) {
        viewModelScope.launch {
            // Helper-backed agents should start from a fresh ACP transport. If we
            // request a new helper handoff before closing the existing socket,
            // the old runtime can survive long enough to be reused, which some
            // stdio agents do not handle correctly on reattach.
            if (_uiState.value.connectionState !is AcpConnectionState.Disconnected) {
                withContext(Dispatchers.IO) {
                    connectionManager.disconnect()
                }
            }

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

            val helperLaunch = when (server) {
                is LaunchableTarget.HelperAgent -> buildDesktopHelperLaunchContext(server)
                is LaunchableTarget.Manual -> Result.success<DesktopHelperLaunchContext?>(null)
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

            val resolvedConfig = launchContext?.config ?: when (server) {
                is LaunchableTarget.Manual -> AcpConnectionConfig(
                    scheme = server.server.scheme,
                    host = server.server.host,
                    preferredAuthMethodId = server.server.preferredAuthMethodId,
                )

                is LaunchableTarget.HelperAgent -> error("Helper-backed targets must launch through the helper runtime flow")
            }

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
                        savePreferredAuthMethod(server.id, authenticatedMethodId)
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
                    val persistedEnvValues = loadPersistedEnvValues(server.id, initializeResult.authMethods)
                    _uiState.update {
                        it.copy(
                            connectingServerId = null,
                            pendingAuthentication = PendingAuthentication(
                                serverId = server.id,
                                serverName = server.name,
                                agentName = initializeResult.agentInfo.name,
                                authMethods = initializeResult.authMethods,
                                persistedEnvValues = persistedEnvValues,
                                authErrorMessage = initializeResult.authErrorMessage,
                                helperRuntime = launchContext?.let { PendingHelperRuntime(runtimeId = it.runtimeId) },
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

    fun authenticate(serverId: String, methodId: String, envValues: Map<String, String> = emptyMap()) {
        viewModelScope.launch {
            val pending = _uiState.value.pendingAuthentication
            if (pending == null || pending.serverId != serverId) return@launch
            val method = pending.authMethods.firstOrNull { it.id == methodId } ?: return@launch

            _uiState.update {
                it.copy(
                    connectingServerId = serverId,
                    showConnectionError = null,
                    pendingAuthentication = pending.copy(authErrorMessage = null),
                )
            }

            if (method.type == "env" && pending.helperRuntime != null) {
                authenticateHelperEnvVar(
                    pending = pending,
                    method = method,
                    envValues = envValues,
                )
                return@launch
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

            savePreferredAuthMethod(serverId, methodId)

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

    fun retryPendingAuthentication(serverId: String) {
        viewModelScope.launch {
            val server = withContext(Dispatchers.IO) {
                launchableTargetRepository.getTarget(serverId)
            } ?: return@launch

            withContext(Dispatchers.IO) {
                connectionManager.disconnect()
            }
            connectAndOpenServer(server)
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
                authEnvValueStore.deleteValues(serverId)
                sessionRepository.clearSessions(serverId)
                sessionSettingsRepository.deleteSettings(serverId)
                launchableTargetRepository.deleteTarget(serverId)
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

    private suspend fun authenticateHelperEnvVar(
        pending: PendingAuthentication,
        method: AcpAuthMethodInfo,
        envValues: Map<String, String>,
    ) {
        val helperRuntime = pending.helperRuntime ?: return
        val server = withContext(Dispatchers.IO) {
            launchableTargetRepository.getTarget(pending.serverId)
        } ?: run {
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = pending.copy(authErrorMessage = "Server was removed before authentication could complete."),
                )
            }
            return
        }
        val helperTarget = server as? LaunchableTarget.HelperAgent ?: run {
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = pending.copy(authErrorMessage = "Desktop companion was not found for ${server.name}."),
                )
            }
            return
        }
        val helperSource = helperTarget.helperSource
        if (helperSource.helperCredential.isBlank()) {
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = pending.copy(authErrorMessage = "Desktop companion is not paired."),
                )
            }
            return
        }

        val envPayload = buildEnvPayload(method, envValues)
        persistEnvValues(server.id, method, envValues)
        val handoff = runCatching {
            withContext(Dispatchers.IO) {
                helperRepository.restartRuntime(
                    scheme = helperSource.scheme,
                    host = helperSource.host,
                    helperCredential = helperSource.helperCredential,
                    runtimeId = helperRuntime.runtimeId,
                    envVars = envPayload,
                )
            }
        }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = pending.copy(authErrorMessage = error.message ?: "Failed to restart ${server.name}."),
                )
            }
            return
        }

        val updatedPending = pending.copy(
            authErrorMessage = null,
            helperRuntime = PendingHelperRuntime(runtimeId = handoff.runtimeId),
        )
        _uiState.update { it.copy(pendingAuthentication = updatedPending) }

        withContext(Dispatchers.IO) {
            connectionManager.disconnect()
        }

        val reconnected = withContext(Dispatchers.IO) {
            connectionManager.connect(
                AcpConnectionConfig(
                    scheme = helperSource.scheme,
                    host = helperSource.host,
                    webSocketUrl = resolveDesktopHelperWebSocketUrl(helperSource, handoff),
                    preferredAuthMethodId = method.id,
                    helperRuntimeId = handoff.runtimeId,
                    helperSourceId = helperSource.id,
                )
            )
        }
        if (!reconnected) {
            val message = connectionManager.diagnostics.value.recentErrors
                .lastOrNull { entry -> entry.source == "connect" || entry.source == "connection" }
                ?.message
                ?: "Failed to reconnect to ${server.name}"
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = updatedPending.copy(authErrorMessage = message),
                    showConnectionError = message,
                )
            }
            return
        }

        val initializeResult = withContext(Dispatchers.IO) {
            connectionManager.initialize()
        }
        if (initializeResult == null) {
            val message = buildInitializeFailureMessage(
                server = server,
                helperSource = helperSource,
                runtimeId = handoff.runtimeId,
            )
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = updatedPending.copy(authErrorMessage = message),
                    showConnectionError = shortInitializeFailureMessage(server),
                )
            }
            return
        }

        val capabilityLabels = initializeResult.agentCapabilities.displayLabels()
        val persistedEnvValues = loadPersistedEnvValues(server.id, initializeResult.authMethods)
        _uiState.update {
            it.copy(
                pendingAuthentication = updatedPending.copy(
                    agentName = initializeResult.agentInfo.name,
                    authMethods = initializeResult.authMethods,
                    persistedEnvValues = persistedEnvValues,
                    authErrorMessage = null,
                ),
                connectedServerState = it.connectedServerState?.copy(
                    agentName = initializeResult.agentInfo.name,
                    capabilities = capabilityLabels,
                    isInitializing = false,
                ),
            )
        }

        when (val result = withContext(Dispatchers.IO) {
            connectionManager.authenticate(method.id)
        }) {
            is AcpAuthenticateResult.Failure -> {
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        pendingAuthentication = it.pendingAuthentication?.copy(authErrorMessage = result.message),
                    )
                }
                return
            }

            AcpAuthenticateResult.Success -> Unit
        }

        savePreferredAuthMethod(server.id, method.id)
        _uiState.update {
            it.copy(
                connectingServerId = null,
                pendingAuthentication = null,
                connectedServerState = it.connectedServerState?.copy(isInitializing = false),
            )
        }
        openConnectedServer(server.id)
    }

    private fun buildEnvPayload(method: AcpAuthMethodInfo, envValues: Map<String, String>): Map<String, String> {
        return buildMap {
            method.envVars.forEach { envVar ->
                val value = envValues[envVar.name]?.trim().orEmpty()
                if (value.isNotEmpty() || !envVar.optional) {
                    put(envVar.name, value)
                }
            }
        }
    }

    private suspend fun loadPersistedEnvValues(
        serverId: String,
        authMethods: List<AcpAuthMethodInfo>,
    ): Map<String, String> {
        val allowedNames = authMethods
            .flatMap { method -> method.envVars }
            .mapTo(linkedSetOf()) { envVar -> envVar.name }
        if (allowedNames.isEmpty()) {
            return emptyMap()
        }
        return withContext(Dispatchers.IO) {
            authEnvValueStore.getValues(serverId)
                .filterKeys { key -> key in allowedNames }
        }
    }

    private suspend fun persistEnvValues(
        serverId: String,
        method: AcpAuthMethodInfo,
        envValues: Map<String, String>,
    ) {
        if (method.envVars.isEmpty()) {
            return
        }
        withContext(Dispatchers.IO) {
            authEnvValueStore.updateValues(
                serverId = serverId,
                envVarNames = method.envVars.mapTo(linkedSetOf()) { envVar -> envVar.name },
                envValues = envValues.filterKeys { key -> method.envVars.any { envVar -> envVar.name == key } },
            )
        }
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

    private suspend fun savePreferredAuthMethod(targetId: String, methodId: String) {
        withContext(Dispatchers.IO) {
            launchableTargetRepository.updatePreferredAuthMethod(targetId, methodId)
        }
    }

    /**
     * Desktop helper agents need a two-stage launch: start or reuse the helper
     * runtime, then request a runtime-scoped ACP WebSocket handoff.
     */
    /**
     * Starts the selected helper-backed agent and converts the helper handoff
     * response into an ACP connection config Ferngeist can reconnect with later.
     */
    private suspend fun buildDesktopHelperLaunchContext(server: LaunchableTarget.HelperAgent): Result<DesktopHelperLaunchContext> {
        val helperSource = server.helperSource
        if (helperSource.helperCredential.isBlank()) {
            return Result.failure(IllegalStateException("Desktop companion is not paired"))
        }

        return runCatching {
            val runtime = withContext(Dispatchers.IO) {
                helperRepository.startAgent(
                    scheme = helperSource.scheme,
                    host = helperSource.host,
                    helperCredential = helperSource.helperCredential,
                    agentId = server.binding.agentId,
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
                    scheme = helperSource.scheme,
                    host = helperSource.host,
                    webSocketUrl = resolveDesktopHelperWebSocketUrl(helperSource, handoff),
                    preferredAuthMethodId = server.preferredAuthMethodId,
                    helperRuntimeId = runtime.id,
                    helperSourceId = helperSource.id,
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
        server: LaunchableTarget,
        helperSource: DesktopHelperSource?,
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
        return "$diagnosticMessage\nDesktop companion runtime: $runtimeHint"
    }

    private fun shortInitializeFailureMessage(server: LaunchableTarget): String {
        return "Failed to initialize session with ${server.name}. See logcat for details."
    }

    private fun logConnectionFailure(server: LaunchableTarget, phase: String, message: String) {
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
        helperSource: DesktopHelperSource,
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
