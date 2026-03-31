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
import com.tamimarafat.ferngeist.core.model.ServerSourceKind
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperConnectResponse
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
    val helperRuntimeId: String? = null,
    val pendingAction: PendingAuthAction,
)

sealed interface PendingAuthAction {
    data object RefreshSessions : PendingAuthAction
    data class CreateSession(val cwd: String) : PendingAuthAction
}

@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val sessionRepository: SessionRepository,
    private val connectionManager: AcpConnectionManager,
    private val helperRepository: DesktopHelperRepository,
    private val authEnvValueStore: AuthEnvValueStore,
) : ViewModel() {

    val serverId: String = savedStateHandle.get<String>("serverId") ?: ""

    val server: StateFlow<ServerConfig?> = serverRepository.getServers()
        .map { servers -> servers.find { it.id == serverId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
            runCatching {
                connectionManager.listSessions()
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

            if (method.type == "env" && pending.helperRuntimeId != null) {
                authenticateHelperEnvVar(pending, method, envValues)
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

            server.value?.let { savePreferredAuthMethod(it, methodId) }
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
                connectionManager.connect(
                    AcpConnectionConfig(
                        scheme = server.scheme,
                        host = server.host,
                        preferredAuthMethodId = server.preferredAuthMethodId,
                        helperSourceId = server.helperSourceId,
                    )
                )
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
            helperRuntimeId = connectionManager.currentConnectionConfig()?.helperRuntimeId,
            pendingAction = action,
        )
        _isLoading.value = false
        return true
    }

    private suspend fun authenticateHelperEnvVar(
        pending: SessionListPendingAuthentication,
        method: AcpAuthMethodInfo,
        envValues: Map<String, String>,
    ) {
        // Helper-backed env auth requires a fresh process environment. Restart
        // the helper runtime with the saved values, reconnect, authenticate,
        // then retry the original session action.
        val currentServer = server.value ?: run {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Server was removed before authentication could complete.")
            }
            _isLoading.value = false
            return
        }
        val helperSource = resolveDesktopHelperSource(currentServer) ?: run {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Desktop companion was not found for ${currentServer.name}.")
            }
            _isLoading.value = false
            return
        }
        if (helperSource.helperCredential.isBlank()) {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Desktop companion is not paired.")
            }
            _isLoading.value = false
            return
        }

        val runtimeId = pending.helperRuntimeId ?: run {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Helper runtime context is missing for ${currentServer.name}.")
            }
            _isLoading.value = false
            return
        }

        persistEnvValues(currentServer.id, method, envValues)
        val handoff = runCatching {
            withContext(Dispatchers.IO) {
                helperRepository.restartRuntime(
                    scheme = helperSource.scheme,
                    host = helperSource.host,
                    helperCredential = helperSource.helperCredential,
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
                    scheme = currentServer.scheme,
                    host = currentServer.host,
                    webSocketUrl = resolveDesktopHelperWebSocketUrl(helperSource, handoff),
                    preferredAuthMethodId = method.id,
                    helperRuntimeId = handoff.runtimeId,
                    helperSourceId = helperSource.id,
                )
            )
        }
        if (!reconnected) {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Failed to reconnect to ${currentServer.name}", helperRuntimeId = handoff.runtimeId)
            }
            _isLoading.value = false
            return
        }

        val initializeResult = withContext(Dispatchers.IO) {
            connectionManager.initialize()
        }
        if (initializeResult == null) {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = "Failed to initialize ${currentServer.name}", helperRuntimeId = handoff.runtimeId)
            }
            _isLoading.value = false
            return
        }

        when (val result = connectionManager.authenticate(method.id)) {
            is AcpAuthenticateResult.Failure -> {
                _pendingAuthentication.update {
                    it?.copy(authErrorMessage = result.message, helperRuntimeId = handoff.runtimeId)
                }
                _isLoading.value = false
                return
            }

            AcpAuthenticateResult.Success -> Unit
        }

        savePreferredAuthMethod(currentServer, method.id)
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
        // Omit blank optional values so helper-managed restarts only inject the
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

    private suspend fun resolveDesktopHelperSource(server: ServerConfig): ServerConfig? {
        val helperSourceId = server.helperSourceId ?: return server.takeIf { it.sourceKind == ServerSourceKind.DESKTOP_HELPER }
        return withContext(Dispatchers.IO) {
            serverRepository.getServer(helperSourceId)
        }
    }

    private fun resolveDesktopHelperWebSocketUrl(
        helperSource: ServerConfig,
        handoff: DesktopHelperConnectResponse,
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

    private fun savePreferredAuthMethod(server: ServerConfig, methodId: String) {
        if (server.preferredAuthMethodId == methodId) return
        viewModelScope.launch(Dispatchers.IO) {
            serverRepository.updateServer(server.copy(preferredAuthMethodId = methodId))
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
