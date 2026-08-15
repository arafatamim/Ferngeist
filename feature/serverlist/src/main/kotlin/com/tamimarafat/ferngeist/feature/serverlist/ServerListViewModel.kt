package com.tamimarafat.ferngeist.feature.serverlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.connection.displayLabels
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.RecentCwdStore
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import com.tamimarafat.ferngeist.feature.serverlist.consent.AgentLaunchConsentStore
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import com.tamimarafat.ferngeist.gateway.resolveGatewayWebSocketUrl
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
    val pendingLaunchConsent: PendingLaunchConsent? = null,
)

data class RecentSession(
    val sessionId: String,
    val title: String,
    val serverId: String,
    val target: LaunchableTarget,
    val cwd: String?,
    val updatedAt: Long?,
)

data class PendingAuthentication(
    val serverId: String,
    val serverName: String,
    val agentName: String,
    val authMethods: List<AcpAuthMethodInfo>,
    val persistedEnvValues: Map<String, String> = emptyMap(),
    val authErrorMessage: String? = null,
    val gatewayRuntime: PendingGatewayRuntime? = null,
)

data class PendingGatewayRuntime(
    val runtimeId: String,
)

data class PendingLaunchConsent(
    val serverId: String,
    val serverName: String,
    val agentId: String,
    val gatewayHost: String,
)


private const val LOG_TAG = "ServerListViewModel"

private const val RECENT_SESSIONS_LIMIT = 5

@HiltViewModel
class ServerListViewModel
    @Inject
    constructor(
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val launchableTargetRepository: LaunchableTargetRepository,
        private val sessionRepository: SessionRepository,
        private val connectionManager: AcpConnectionManager,
        private val gatewayRepository: GatewayRepository,
        private val authEnvValueStore: AuthEnvValueStore,
        private val agentLaunchConsentStore: AgentLaunchConsentStore,
        private val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
        private val recentCwdStore: RecentCwdStore,
        private val recentSelectionStore: RecentSelectionStore,
    ) : ViewModel() {
        val servers: StateFlow<List<LaunchableTarget>> =
            launchableTargetRepository
                .getTargets()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val hasGateways: StateFlow<Boolean> =
            gatewaySourceRepository
                .getGateways()
                .map { gateways -> gateways.isNotEmpty() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        val recentSessions: StateFlow<List<RecentSession>> =
            sessionRepository
                .getRecentSessions(RECENT_SESSIONS_LIMIT)
                .map { summaries ->
                    summaries
                        .filter { summary ->
                            summary.serverId.isNotBlank().also { valid ->
                                if (!valid) Log.w(LOG_TAG, "Skipping session ${summary.id}: blank serverId")
                            }
                        }.mapNotNull { summary ->
                            val target =
                                launchableTargetRepository.getTarget(summary.serverId) ?: return@mapNotNull null
                            RecentSession(
                                sessionId = summary.id,
                                title = summary.title ?: "Untitled session",
                                serverId = summary.serverId,
                                target = target,
                                cwd = summary.cwd,
                                updatedAt = summary.updatedAt,
                            )
                        }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

            // Mark list as loaded on first emission
            viewModelScope.launch {
                servers.collect { _isLoading.value = false }
            }
        }

        /**
         * Full connection orchestration flow:
         * connect → initialize → navigate
         */
        /**
         * Checks gateway launch consent; surfaces the pending-consent prompt when
         * the agent has not been approved yet. Returns false to stop the connect.
         */
        private suspend fun hasLaunchConsent(server: LaunchableTarget): Boolean {
            if (server !is LaunchableTarget.GatewayAgent) return true
            val consentKey =
                buildLaunchConsentKey(
                    gatewaySourceId = server.gatewaySource.id,
                    agentId = server.binding.agentId,
                )
            val hasConsent =
                withContext(Dispatchers.IO) {
                    agentLaunchConsentStore.hasConsent(consentKey)
                }
            if (hasConsent) return true
            _uiState.update {
                it.copy(
                    pendingLaunchConsent =
                        PendingLaunchConsent(
                            serverId = server.id,
                            serverName = server.name,
                            agentId = server.binding.agentId,
                            gatewayHost = server.gatewaySource.host,
                        ),
                )
            }
            return false
        }

        fun connectAndOpenServer(server: LaunchableTarget) {
            viewModelScope.launch {
                // REUSE GUARD: If already connected to this server (and no pending auth), skip re-connection
                if (_uiState.value.connectedServerState?.serverId == server.id &&
                    connectionManager.isConnected &&
                    _uiState.value.pendingAuthentication == null
                ) {
                    openConnectedServer(server.id, server.name)
                    return@launch
                }

                if (!hasLaunchConsent(server)) return@launch

                // Gateway-backed agents should start from a fresh ACP transport. If we
                // request a new gateway handoff before closing the existing socket,
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
                        pendingLaunchConsent = null,
                        connectedServerState =
                            ConnectedServerState(
                                serverId = server.id,
                                isInitializing = true,
                            ),
                    )
                }

                // Only gateway-backed targets require a launch context. For manual
                // servers null means "no context needed", so proceed with null.
                val launchContext = resolveLaunchContext(server)
                if (server is LaunchableTarget.GatewayAgent && launchContext == null) {
                    return@launch
                }
                val resolvedConfig = resolveConnectionConfig(server, launchContext)

                val initializeResult =
                    connectAndInitialize(server, launchContext, resolvedConfig) ?: return@launch
                handleInitializeResult(server, launchContext, initializeResult)
            }
        }

    /**
     * Builds the gateway launch context, surfacing a launch error when the
     * gateway runtime could not be started. Returns null to stop the connect.
     */
    private suspend fun resolveLaunchContext(server: LaunchableTarget): GatewayLaunchContext? {
        val gatewayLaunch =
            when (server) {
                is LaunchableTarget.GatewayAgent -> buildGatewayLaunchContext(gatewayRepository, gatewaySourceRepository, server)
                is LaunchableTarget.Manual -> Result.success<GatewayLaunchContext?>(null)
            }
        return gatewayLaunch.getOrElse { error ->
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    connectionState = AcpConnectionState.Failed(error),
                    connectedServerState = null,
                    showConnectionError = error.message ?: "Failed to launch ${server.name}",
                )
            }
            null
        }
    }

    /** Resolves the transport config from the launch context or the manual server. */
    private fun resolveConnectionConfig(
        server: LaunchableTarget,
        launchContext: GatewayLaunchContext?,
    ): AcpConnectionConfig =
        launchContext?.config ?: when (server) {
            is LaunchableTarget.Manual ->
                AcpConnectionConfig(
                    scheme = server.server.scheme,
                    host = server.server.host,
                    preferredAuthMethodId = server.server.preferredAuthMethodId,
                    serverDisplayName = server.name,
                )

            is LaunchableTarget.GatewayAgent ->
                error(
                    "Gateway-backed targets must launch through the gateway runtime flow",
                )
        }

    /**
     * Connects the transport and runs the initialize handshake. Returns the
     * initialize result, or null after surfacing a connection/init error.
     */
    private suspend fun connectAndInitialize(
        server: LaunchableTarget,
        launchContext: GatewayLaunchContext?,
        resolvedConfig: AcpConnectionConfig,
    ): AcpInitializeResult? {
        val connected =
            withContext(Dispatchers.IO) {
                connectionManager.connect(resolvedConfig)
            }
        if (!connected) {
            val connectMessage =
                connectionManager.diagnostics.value.recentErrors
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
            return null
        }

        // Step 2: Initialize and get agent info
        val initializeResult =
            withContext(Dispatchers.IO) {
                connectionManager.initialize()
            }
        if (initializeResult == null) {
            val initializeDetail =
                buildInitializeFailureMessage(
                    connectionManager = connectionManager,
                    gatewayRepository = gatewayRepository,
                    gatewaySourceRepository = gatewaySourceRepository,
                    server = server,
                    gatewaySource = launchContext?.gatewaySource,
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
            return null
        }
        return initializeResult
    }

    /**
     * Applies the initialize outcome: marks the server ready and opens it, or
     * surfaces the pending-authentication prompt with persisted env values.
     */
    private suspend fun handleInitializeResult(
        server: LaunchableTarget,
        launchContext: GatewayLaunchContext?,
        initializeResult: AcpInitializeResult,
    ) {
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
                        connectedServerState =
                            it.connectedServerState?.copy(
                                agentName = initializeResult.agentInfo.name,
                                capabilities = capabilityLabels,
                                isInitializing = false,
                            ),
                    )
                }
                openConnectedServer(server.id, server.name)
            }

            is AcpInitializeResult.AuthenticationRequired -> {
                val capabilityLabels = initializeResult.agentCapabilities.displayLabels()
                val persistedEnvValues = loadPersistedEnvValues(authEnvValueStore, server.id, initializeResult.authMethods)
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        pendingAuthentication =
                            PendingAuthentication(
                                serverId = server.id,
                                serverName = server.name,
                                agentName = initializeResult.agentInfo.name,
                                authMethods = initializeResult.authMethods,
                                persistedEnvValues = persistedEnvValues,
                                authErrorMessage = initializeResult.authErrorMessage,
                                gatewayRuntime =
                                    launchContext?.let {
                                        PendingGatewayRuntime(runtimeId = it.runtimeId)
                                    },
                            ),
                        connectedServerState =
                            it.connectedServerState?.copy(
                                agentName = initializeResult.agentInfo.name,
                                capabilities = capabilityLabels,
                                isInitializing = false,
                            ),
                    )
                }
            }
        }
    }

        fun authenticate(
            serverId: String,
            methodId: String,
            envValues: Map<String, String> = emptyMap(),
        ) {
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

                if (method.type == "env" && pending.gatewayRuntime != null) {
                    authenticateGatewayEnvVar(
                        pending = pending,
                        method = method,
                        envValues = envValues,
                    )
                    return@launch
                }

                when (
                    val result =
                        withContext(Dispatchers.IO) {
                            connectionManager.authenticate(methodId)
                        }
                ) {
                    is AcpAuthenticateResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                connectingServerId = null,
                                pendingAuthentication =
                                    pending.copy(
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
                val serverName = pending.serverName
                openConnectedServer(serverId, serverName)
            }
        }

        fun retryPendingAuthentication(serverId: String) {
            viewModelScope.launch {
                val server =
                    withContext(Dispatchers.IO) {
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

        fun dismissLaunchConsent() {
            _uiState.update { it.copy(pendingLaunchConsent = null) }
        }

        fun confirmLaunchConsent(serverId: String) {
            viewModelScope.launch {
                val server =
                    withContext(Dispatchers.IO) {
                        launchableTargetRepository.getTarget(serverId)
                    }
                val gatewayTarget =
                    server as? LaunchableTarget.GatewayAgent ?: run {
                        _uiState.update { it.copy(pendingLaunchConsent = null) }
                        return@launch
                    }
                val consentKey =
                    buildLaunchConsentKey(
                        gatewaySourceId = gatewayTarget.gatewaySource.id,
                        agentId = gatewayTarget.binding.agentId,
                    )
                withContext(Dispatchers.IO) {
                    agentLaunchConsentStore.setConsent(consentKey, true)
                }
                _uiState.update { it.copy(pendingLaunchConsent = null) }
                connectAndOpenServer(gatewayTarget)
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
                withContext(Dispatchers.IO) {
                    val target = launchableTargetRepository.getTarget(serverId)
                    if (target is LaunchableTarget.GatewayAgent) {
                        val consentKey =
                            buildLaunchConsentKey(
                                gatewaySourceId = target.gatewaySource.id,
                                agentId = target.binding.agentId,
                            )
                        agentLaunchConsentStore.clearByPrefix(consentKey)
                    }
                    authEnvValueStore.deleteValues(serverId)
                    recentCwdStore.clear(serverId)
                    // Prefix-based cleanup: clearByPrefix removes all entries whose DataStore key
                    // starts with the given prefix. Trailing ":" prevents cross-server ID matches.
                    recentSelectionStore.clearByPrefix("config_option:$serverId:")
                    recentSelectionStore.clearByPrefix("commands:$serverId:")
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

        /**
     * Runs initialize + authenticate after a gateway reconnection. Returns false
     * (after updating UI state with the error) when initialization or auth fails.
     */
    private suspend fun completeGatewayAuthentication(
        server: LaunchableTarget,
        gatewaySource: com.tamimarafat.ferngeist.core.model.GatewaySource,
        handoff: com.tamimarafat.ferngeist.gateway.GatewayConnectResponse,
        updatedPending: PendingAuthentication,
        method: AcpAuthMethodInfo,
    ): Boolean {
        val initializeResult =
            withContext(Dispatchers.IO) {
                connectionManager.initialize()
            }
        if (initializeResult == null) {
            val message =
                buildInitializeFailureMessage(
                    connectionManager = connectionManager,
                    gatewayRepository = gatewayRepository,
                    gatewaySourceRepository = gatewaySourceRepository,
                    server = server,
                    gatewaySource = gatewaySource,
                    runtimeId = handoff.runtimeId,
                )
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = updatedPending.copy(authErrorMessage = message),
                    showConnectionError = shortInitializeFailureMessage(server),
                )
            }
            return false
        }

        applyInitializeUiState(server.id, updatedPending, initializeResult)
        if (!authenticateWithMethod(method.id)) return false
        savePreferredAuthMethod(server.id, method.id)
        _uiState.update {
            it.copy(
                connectingServerId = null,
                pendingAuthentication = null,
                connectedServerState = it.connectedServerState?.copy(isInitializing = false),
            )
        }
        return true
    }

    /** Applies the agent-info + env values from initialize to the pending prompt. */
    private suspend fun applyInitializeUiState(
        serverId: String,
        updatedPending: PendingAuthentication,
        initializeResult: AcpInitializeResult,
    ) {
        val capabilityLabels = initializeResult.agentCapabilities.displayLabels()
        val persistedEnvValues = loadPersistedEnvValues(authEnvValueStore, serverId, initializeResult.authMethods)
        _uiState.update {
            it.copy(
                pendingAuthentication =
                    updatedPending.copy(
                        agentName = initializeResult.agentInfo.name,
                        authMethods = initializeResult.authMethods,
                        persistedEnvValues = persistedEnvValues,
                        authErrorMessage = null,
                    ),
                connectedServerState =
                    it.connectedServerState?.copy(
                        agentName = initializeResult.agentInfo.name,
                        capabilities = capabilityLabels,
                        isInitializing = false,
                    ),
            )
        }
    }

    /** Runs the authenticate handshake; surfaces a failure and returns false. */
    private suspend fun authenticateWithMethod(methodId: String): Boolean {
        val result =
            withContext(Dispatchers.IO) {
                connectionManager.authenticate(methodId)
            }
        if (result is AcpAuthenticateResult.Failure) {
            _uiState.update {
                it.copy(
                    connectingServerId = null,
                    pendingAuthentication = it.pendingAuthentication?.copy(authErrorMessage = result.message),
                )
            }
            return false
        }
        return true
    }

    private suspend fun resolveGatewayAuthContext(
        pending: PendingAuthentication,
    ): GatewayAuthContext? =
        resolveGatewayAuthContext(
            pending = pending,
            uiState = _uiState,
            launchableTargetRepository = launchableTargetRepository,
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
        )

    private suspend fun authenticateGatewayEnvVar(
            pending: PendingAuthentication,
            method: AcpAuthMethodInfo,
            envValues: Map<String, String>,
        ) {
            val gatewayRuntime = pending.gatewayRuntime ?: return
            val gatewayContext =
                resolveGatewayAuthContext(pending) ?: return
            val server = gatewayContext.server
            val gatewaySource = gatewayContext.gatewaySource

            val envPayload = buildEnvPayload(method, envValues)
            persistEnvValues(authEnvValueStore, server.id, method, envValues)
            val handoff =
                restartGatewayRuntime(pending, server, gatewaySource, gatewayRuntime, envPayload) ?: return
            val updatedPending =
                pending.copy(
                    authErrorMessage = null,
                    gatewayRuntime = PendingGatewayRuntime(runtimeId = handoff.runtimeId),
                )
            _uiState.update { it.copy(pendingAuthentication = updatedPending) }

            if (
                !reconnectWithHandoff(server, gatewaySource, method, updatedPending, handoff) ||
                !completeGatewayAuthentication(server, gatewaySource, handoff, updatedPending, method)
            ) {
                return
            }
            openConnectedServer(server.id, server.name)
        }

        /**
         * Restarts the gateway runtime with the env payload. Returns the handoff,
         * or null after surfacing the restart error.
         */
        private suspend fun restartGatewayRuntime(
            pending: PendingAuthentication,
            server: LaunchableTarget,
            gatewaySource: com.tamimarafat.ferngeist.core.model.GatewaySource,
            gatewayRuntime: PendingGatewayRuntime,
            envPayload: Map<String, String>,
        ): com.tamimarafat.ferngeist.gateway.GatewayConnectResponse? =
            runCatching {
                withContext(Dispatchers.IO) {
                    gatewayRepository.restartRuntime(
                        scheme = gatewaySource.scheme,
                        host = gatewaySource.host,
                        gatewayCredential = gatewaySource.gatewayCredential,
                        runtimeId = gatewayRuntime.runtimeId,
                        envVars = envPayload,
                    )
                }
            }.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        connectingServerId = null,
                        pendingAuthentication =
                            pending.copy(
                                authErrorMessage =
                                    error.message ?: "Failed to restart ${server.name}.",
                            ),
                    )
                }
                null
            }

        /**
         * Disconnects the old transport and reconnects with the new handoff.
         * Returns false after surfacing the reconnect error.
         */
        private suspend fun reconnectWithHandoff(
            server: LaunchableTarget,
            gatewaySource: com.tamimarafat.ferngeist.core.model.GatewaySource,
            method: AcpAuthMethodInfo,
            updatedPending: PendingAuthentication,
            handoff: com.tamimarafat.ferngeist.gateway.GatewayConnectResponse,
        ): Boolean {
            withContext(Dispatchers.IO) {
                connectionManager.disconnect()
            }
            val reconnected =
                withContext(Dispatchers.IO) {
                    connectionManager.connect(
                        AcpConnectionConfig(
                            scheme = gatewaySource.scheme,
                            host = gatewaySource.host,
                            webSocketUrl = resolveGatewayWebSocketUrl(gatewaySource, handoff),
                            webSocketBearerToken = handoff.bearerToken,
                            preferredAuthMethodId = method.id,
                            gatewayRuntimeId = handoff.runtimeId,
                            gatewaySourceId = gatewaySource.id,
                            sessionId = handoff.sessionId,
                            attachToken = handoff.attachToken,
                            gatewayScheme = gatewaySource.scheme,
                            gatewayHost = gatewaySource.host,
                            gatewayCredential = gatewaySource.gatewayCredential,
                        ),
                    )
                }
            if (reconnected) return true
            val message =
                connectionManager.diagnostics.value.recentErrors
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
            return false
        }

        private fun handleManagerEvent(event: AcpManagerEvent) {
            when (event) {
                is AcpManagerEvent.Initialized -> {
                    _uiState.update { current ->
                        current.copy(
                            connectedServerState =
                                current.connectedServerState?.copy(
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
                        if (pendingAuthentication != null &&
                            current.connectingServerId == pendingAuthentication.serverId
                        ) {
                            current.copy(
                                connectingServerId = null,
                                pendingAuthentication = pendingAuthentication.copy(authErrorMessage = message),
                                connectedServerState = current.connectedServerState?.copy(isInitializing = false),
                            )
                        } else {
                            current.copy(
                                showConnectionError = message,
                                connectedServerState =
                                    current.connectedServerState?.copy(
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

        private suspend fun savePreferredAuthMethod(
            targetId: String,
            methodId: String,
        ) {
            withContext(Dispatchers.IO) {
                launchableTargetRepository.updatePreferredAuthMethod(targetId, methodId)
            }
        }

        private suspend fun openConnectedServer(
            serverId: String,
            serverName: String,
        ) {
            _events.emit(
                ServerListEvent.NavigateToSessions(
                    serverId = serverId,
                    serverName = serverName,
                    openCreateSessionDialog = false,
                ),
            )
        }
    }

private fun buildLaunchConsentKey(
    gatewaySourceId: String,
    agentId: String,
): String = "$gatewaySourceId:$agentId"

sealed interface ServerListEvent {
    data class NavigateToSessions(
        val serverId: String,
        val serverName: String,
        val sessions: List<SessionSummary> = emptyList(),
        val openCreateSessionDialog: Boolean = false,
    ) : ServerListEvent

    data class ShowError(
        val message: String,
    ) : ServerListEvent
}
