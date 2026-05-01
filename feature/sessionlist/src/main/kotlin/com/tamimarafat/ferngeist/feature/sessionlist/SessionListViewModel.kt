package com.tamimarafat.ferngeist.feature.sessionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.LaunchableTargetSessionSettings
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import com.tamimarafat.ferngeist.feature.serverlist.gateway.GatewayConnectResponse
import com.tamimarafat.ferngeist.feature.serverlist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.feature.serverlist.gateway.refreshGatewaySourceIfNeeded
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
import javax.inject.Inject

/**
 * UI state for an ACP auth challenge discovered while the session list is
 * trying to list, create, or load sessions.
 */
data class SessionListPendingAuthentication(
    val serverId: String,
    val serverName: String,
    val agentName: String,
    val authMethods: List<AcpAuthMethodInfo>,
    val preferredAuthMethodId: String? = null,
    val persistedEnvValues: Map<String, String> = emptyMap(),
    val authErrorMessage: String? = null,
    val gatewayRuntimeId: String? = null,
    val pendingAction: PendingAuthAction,
)

sealed interface PendingAuthAction {
    data object RefreshSessions : PendingAuthAction
    data class CreateSession(val cwd: String) : PendingAuthAction
}

@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val sessionRepository: SessionRepository,
    private val connectionManager: AcpConnectionManager,
    private val gatewayRepository: GatewayRepository,
    private val authEnvValueStore: AuthEnvValueStore,
    private val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
) : ViewModel() {

    val serverId: String = savedStateHandle.get<String>("serverId") ?: ""

    val server: StateFlow<LaunchableTarget?> = launchableTargetRepository.getTargets()
        .map { servers -> servers.find { it.id == serverId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sessionSettings: StateFlow<LaunchableTargetSessionSettings> = sessionSettingsRepository.getSettings(serverId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LaunchableTargetSessionSettings(targetId = serverId))

    val sessions: StateFlow<List<SessionSummary>> = sessionRepository.getSessions(serverId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val connectionState: StateFlow<AcpConnectionState> = connectionManager.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AcpConnectionState.Disconnected)
    val agentCapabilities: StateFlow<AcpAgentCapabilities?> = connectionManager.agentCapabilities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val connectionDiagnostics: StateFlow<ConnectionDiagnostics> = connectionManager.diagnostics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionDiagnostics())

    private val _events = MutableSharedFlow<SessionListEvent>()
    val events = _events.asSharedFlow()

    private val _pendingAuthentication = MutableStateFlow<SessionListPendingAuthentication?>(null)
    val pendingAuthentication: StateFlow<SessionListPendingAuthentication?> = _pendingAuthentication.asStateFlow()

    init {
        refreshSessions()
    }

    fun importSessions(sessions: List<SessionSummary>) {
        viewModelScope.launch {
            replaceSessions(sessions)
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            if (!connectionManager.isConnected) {
                _isLoading.value = false
                return@launch
            }

            val capabilities = connectionManager.agentCapabilities.value
            if (capabilities != null && !capabilities.session.list) {
                _isLoading.value = false
                return@launch
            }

            _isLoading.value = true
            val settings = sessionSettingsRepository.getSettingsBlocking(serverId)
            val cwd = settings?.cwd?.trim()?.ifBlank { null }
            runCatching {
                connectionManager.listSessions(cwd = cwd)
            }.onSuccess { remoteSessions ->
                replaceSessions(remoteSessions)
            }.onFailure { error ->
                if (handleAuthenticationRequired(error, PendingAuthAction.RefreshSessions)) {
                    return@launch
                }
                _events.emit(SessionListEvent.ShowError(formatAcpErrorMessage(error, "Failed to load sessions")))
            }
            _isLoading.value = false
        }
    }

    fun createSession(cwd: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val normalizedCwd = cwd.trim().ifBlank { "/" }
            runCatching {
                connectionManager.createSession(normalizedCwd)
            }.onSuccess { bridge ->
                if (bridge == null) {
                    _events.emit(SessionListEvent.ShowError("Failed to create a new session"))
                } else {
                    val summary = SessionSummary(
                        id = bridge.sessionId,
                        title = null,
                        cwd = normalizedCwd,
                        updatedAt = System.currentTimeMillis()
                    )
                    sessionRepository.upsertSession(serverId, summary)
                    _events.emit(
                        SessionListEvent.NavigateToChat(
                            serverId = serverId,
                            sessionId = summary.id,
                            cwd = summary.cwd ?: "/",
                            updatedAt = summary.updatedAt,
                            title = summary.title,
                        )
                    )
                }
            }.onFailure { error ->
                if (handleAuthenticationRequired(error, PendingAuthAction.CreateSession(normalizedCwd))) {
                    return@launch
                }
                _events.emit(SessionListEvent.ShowError(formatAcpErrorMessage(error, "Failed to create a new session")))
            }
            _isLoading.value = false
        }
    }

    fun createSessionWithCurrentCwd() {
        val normalizedCwd = sessionSettings.value.cwd?.trim()?.ifBlank { "/" } ?: "/"
        createSession(normalizedCwd)
    }

    fun updateCurrentCwd(cwd: String) {
        viewModelScope.launch {
            sessionSettingsRepository.updateCwd(serverId, cwd)
            refreshSessions()
        }
    }

    /**
     * Completes the currently pending ACP auth challenge, then retries the
     * session action that originally failed.
     */
    fun authenticate(methodId: String, envValues: Map<String, String> = emptyMap()) {
        viewModelScope.launch {
            val pending = _pendingAuthentication.value ?: return@launch
            val method = pending.authMethods.firstOrNull { it.id == methodId } ?: return@launch

            _isLoading.value = true
            _pendingAuthentication.update { it?.copy(authErrorMessage = null) }

            if (method.type == "env" && pending.gatewayRuntimeId != null) {
                authenticateGatewayEnvVar(pending, method, envValues)
                return@launch
            }

            when (val result = connectionManager.authenticate(methodId)) {
                is AcpAuthenticateResult.Failure -> {
                    _pendingAuthentication.update { it?.copy(authErrorMessage = result.message) }
                    _isLoading.value = false
                    return@launch
                }

                AcpAuthenticateResult.Success -> Unit
            }

            savePreferredAuthMethod(serverId, methodId)
            _pendingAuthentication.value = null
            retryPendingAction(pending.pendingAction)
        }
    }

    /**
     * For manual env-var auth Ferngeist cannot inject credentials into the
     * external process, so the user restarts it outside the app and this method
     * reconnects before retrying the blocked session action.
     */
    fun reconnectPendingAuthentication() {
        viewModelScope.launch {
            val pending = _pendingAuthentication.value ?: return@launch
            val server = server.value ?: return@launch
            _isLoading.value = true
            _pendingAuthentication.update { it?.copy(authErrorMessage = null) }

            withContext(Dispatchers.IO) {
                connectionManager.disconnect()
            }

            val connected = withContext(Dispatchers.IO) {
                when (server) {
                    is LaunchableTarget.Manual -> {
                        connectionManager.connect(
                            AcpConnectionConfig(
                                scheme = server.server.scheme,
                                host = server.server.host,
                                preferredAuthMethodId = server.server.preferredAuthMethodId,
                                serverDisplayName = server.name,
                            )
                        )
                    }

                    is LaunchableTarget.GatewayAgent -> false
                }
            }
            if (!connected) {
                _pendingAuthentication.update {
                    it?.copy(authErrorMessage = "Failed to reconnect to ${server.name}")
                }
                _isLoading.value = false
                return@launch
            }

            val initializeResult = withContext(Dispatchers.IO) {
                connectionManager.initialize()
            }
            if (initializeResult == null) {
                _pendingAuthentication.update {
                    it?.copy(authErrorMessage = "Failed to initialize ${server.name}")
                }
                _isLoading.value = false
                return@launch
            }

            _pendingAuthentication.value = null
            retryPendingAction(pending.pendingAction)
        }
    }

    fun dismissAuthenticationPrompt() {
        _pendingAuthentication.value = null
        _isLoading.value = false
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.deleteSession(serverId, sessionId)
        }
    }

    private suspend fun replaceSessions(newSessions: List<SessionSummary>) {
        sessionRepository.clearSessions(serverId)
        newSessions.forEach { session ->
            sessionRepository.upsertSession(serverId, session)
        }
    }

    private suspend fun handleAuthenticationRequired(
        error: Throwable,
        action: PendingAuthAction,
    ): Boolean {
        // ACP auth is now session-gated. initialize() only advertises methods;
        // the first session/list, session/new, or session/load failure is what
        // actually opens the authentication prompt.
        val authError = error as? AcpAuthenticationRequiredException ?: return false
        val currentServer = server.value ?: return false
        _pendingAuthentication.value = SessionListPendingAuthentication(
            serverId = serverId,
            serverName = currentServer.name,
            agentName = authError.challenge.agentInfo.name,
            authMethods = authError.challenge.authMethods,
            preferredAuthMethodId = currentServer.preferredAuthMethodId,
            persistedEnvValues = loadPersistedEnvValues(currentServer.id, authError.challenge.authMethods),
            authErrorMessage = authError.challenge.message,
            gatewayRuntimeId = connectionManager.currentConnectionConfig()?.gatewayRuntimeId,
            pendingAction = action,
        )
        _isLoading.value = false
        return true
    }

    private suspend fun authenticateGatewayEnvVar(
        pending: SessionListPendingAuthentication,
        method: AcpAuthMethodInfo,
        envValues: Map<String, String>,
    ) {
        // Gateway-backed env auth requires a fresh process environment. Restart
        // the gateway runtime with the saved values, reconnect, authenticate,
        // then retry the original session action.
        val currentServer = server.value ?: run {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Server was removed before authentication could complete.")
            }
            _isLoading.value = false
            return
        }
        val gatewayTarget = currentServer as? LaunchableTarget.GatewayAgent ?: run {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Gateway was not found for ${currentServer.name}.")
            }
            _isLoading.value = false
            return
        }
		val gatewaySource = withContext(Dispatchers.IO) {
			refreshGatewaySourceIfNeeded(gatewayTarget.gatewaySource, gatewayRepository, gatewaySourceRepository)
		}
        if (gatewaySource.gatewayCredential.isBlank()) {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Gateway is not paired.")
            }
            _isLoading.value = false
            return
        }

        val runtimeId = pending.gatewayRuntimeId ?: run {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Gateway runtime context is missing for ${currentServer.name}.")
            }
            _isLoading.value = false
            return
        }

        persistEnvValues(currentServer.id, method, envValues)
        val handoff = runCatching {
            withContext(Dispatchers.IO) {
                gatewayRepository.restartRuntime(
                    scheme = gatewaySource.scheme,
                    host = gatewaySource.host,
                    gatewayCredential = gatewaySource.gatewayCredential,
                    runtimeId = runtimeId,
                    envVars = buildEnvPayload(method, envValues),
                )
            }
        }.getOrElse { error ->
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = error.message ?: "Failed to restart ${currentServer.name}.")
            }
            _isLoading.value = false
            return
        }

        withContext(Dispatchers.IO) {
            connectionManager.disconnect()
        }

        val reconnected = withContext(Dispatchers.IO) {
            connectionManager.connect(
                AcpConnectionConfig(
                    scheme = gatewaySource.scheme,
                    host = gatewaySource.host,
                    webSocketUrl = resolveGatewayWebSocketUrl(gatewaySource, handoff),
                    webSocketBearerToken = handoff.bearerToken,
                    preferredAuthMethodId = method.id,
                    gatewayRuntimeId = handoff.runtimeId,
                    gatewaySourceId = gatewaySource.id,
                    serverDisplayName = currentServer.name,
                )
            )
        }
        if (!reconnected) {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Failed to reconnect to ${currentServer.name}", gatewayRuntimeId = handoff.runtimeId)
            }
            _isLoading.value = false
            return
        }

        val initializeResult = withContext(Dispatchers.IO) {
            connectionManager.initialize()
        }
        if (initializeResult == null) {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Failed to initialize ${currentServer.name}", gatewayRuntimeId = handoff.runtimeId)
            }
            _isLoading.value = false
            return
        }

        when (val result = connectionManager.authenticate(method.id)) {
            is AcpAuthenticateResult.Failure -> {
                _pendingAuthentication.update {
                    it?.copy(authErrorMessage = result.message, gatewayRuntimeId = handoff.runtimeId)
                }
                _isLoading.value = false
                return
            }

            AcpAuthenticateResult.Success -> Unit
        }

        savePreferredAuthMethod(currentServer.id, method.id)
        _pendingAuthentication.value = null
        retryPendingAction(pending.pendingAction)
    }

    private suspend fun retryPendingAction(action: PendingAuthAction) {
        when (action) {
            PendingAuthAction.RefreshSessions -> refreshSessions()
            is PendingAuthAction.CreateSession -> createSession(action.cwd)
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
        if (method.envVars.isEmpty()) return
        withContext(Dispatchers.IO) {
            authEnvValueStore.updateValues(
                serverId = serverId,
                envVarNames = method.envVars.mapTo(linkedSetOf()) { envVar -> envVar.name },
                envValues = envValues,
            )
        }
    }

    private fun buildEnvPayload(method: AcpAuthMethodInfo, envValues: Map<String, String>): Map<String, String> {
        // Omit blank optional values so gateway-managed restarts only inject the
        // variables the user actually provided.
        return buildMap {
            method.envVars.forEach { envVar ->
                val value = envValues[envVar.name]?.trim().orEmpty()
                if (value.isNotEmpty() || !envVar.optional) {
                    put(envVar.name, value)
                }
            }
        }
    }

    private fun resolveGatewayWebSocketUrl(
        gatewaySource: GatewaySource,
        handoff: GatewayConnectResponse,
    ): String {
        val advertisedUrl = handoff.webSocketUrl.trim()
        val advertisedHost = runCatching { URI(advertisedUrl).host?.lowercase() }.getOrNull()
        if (advertisedHost != null && !isUnroutableGatewayHost(advertisedHost)) {
            return advertisedUrl
        }

        val socketScheme = when (gatewaySource.scheme.lowercase()) {
            "https", "wss" -> "wss"
            else -> "ws"
        }
        return "$socketScheme://${gatewaySource.host}${handoff.webSocketPath}"
    }

    private fun isUnroutableGatewayHost(host: String): Boolean {
        return host == "0.0.0.0" || host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    private fun savePreferredAuthMethod(targetId: String, methodId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            launchableTargetRepository.updatePreferredAuthMethod(targetId, methodId)
        }
    }
}

sealed interface SessionListEvent {
    data class NavigateToChat(
        val serverId: String,
        val sessionId: String,
        val cwd: String,
        val updatedAt: Long?,
        val title: String?,
    ) : SessionListEvent
    data class ShowError(val message: String) : SessionListEvent
}
