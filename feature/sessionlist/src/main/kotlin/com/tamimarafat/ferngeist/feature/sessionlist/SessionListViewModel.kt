package com.tamimarafat.ferngeist.feature.sessionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.buildEnvPayload
import com.tamimarafat.ferngeist.acp.bridge.connection.loadPersistedEnvValues
import com.tamimarafat.ferngeist.acp.bridge.connection.persistEnvValues
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub.ListSessionsResult
import com.tamimarafat.ferngeist.acp.bridge.hub.GatewayEndpoint
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.LaunchableTargetSessionSettings
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.RecentCwdStore
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

    data class CreateSession(
        val cwd: String,
    ) : PendingAuthAction
}

/**
 * Coordinates session list data and ACP authentication flows.
 *
 * All transport work (borrow-warm-else-own listing, gateway REST close, the
 * Room session-table write) lives behind [ChatConnectionHub]'s listing seam;
 * this VM only maps results onto UI state, drives the auth dialogs, and keeps
 * env-value persistence. It never holds an [AcpConnectionManager].
 *
 * Carries more functions than detekt's TooManyFunctions budget: the auth
 * recovery flows (gateway runtime restart, env-var persistence, retry
 * dispatch) are built from many small single-purpose helpers that read better
 * split than merged. Suppressed here rather than force-merging cohesive flows.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class SessionListViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val launchableTargetRepository: LaunchableTargetRepository,
        private val sessionRepository: SessionRepository,
        private val gatewayRepository: GatewayRepository,
        private val chatConnectionHub: ChatConnectionHub,
        private val authEnvValueStore: AuthEnvValueStore,
        private val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
        private val recentCwdStore: RecentCwdStore,
    ) : ViewModel() {
        val serverId: String = savedStateHandle.get<String>("serverId") ?: ""

        val server: StateFlow<LaunchableTarget?> =
            launchableTargetRepository
                .getTargets()
                .map { servers -> servers.find { it.id == serverId } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val sessionSettings: StateFlow<LaunchableTargetSessionSettings> =
            sessionSettingsRepository
                .getSettings(serverId)
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    LaunchableTargetSessionSettings(targetId = serverId),
                )

        /** Per-target recent working directories, ordered MRU-first. */
        val recentCwds: StateFlow<List<String>> =
            recentCwdStore
                .getRecentCwds(serverId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val sessions: StateFlow<List<SessionSummary>> =
            sessionRepository
                .getSessions(serverId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        /** Sessions currently holding a live gateway connection (for the row dot). */
        val liveSessionIds: StateFlow<Set<String>> =
            chatConnectionHub
                .connectedSessionIds(serverId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        // NOTE: Separate from _isLoading so PullToRefreshDefaults.LoadingIndicator
        // only shows on user-pull, not on initial load with cached sessions.
        private val _refreshing = MutableStateFlow(false)
        val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
        val connectionState: StateFlow<ChatConnectionState> =
            chatConnectionHub.warmServers
                .map { warmServers ->
                    val warm = serverId in warmServers
                    // One source of truth: hub-tracked chats holding live
                    // transports. Listing transports live inside the hub seam
                    // and hang up after every list, so they must not drive the pill.
                    if (warm) ChatConnectionState.Connected else ChatConnectionState.Disconnected
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatConnectionState.Disconnected)

        /** Last-known agent capabilities for this server (from hub listings). */
        private val _agentCapabilities =
            MutableStateFlow<AgentCapabilities?>(
                chatConnectionHub.listingAgentCapabilities(serverId),
            )
        val agentCapabilities: StateFlow<AgentCapabilities?> = _agentCapabilities.asStateFlow()

        /** Last-known transport diagnostics for this server (from hub listings). */
        private val _connectionDiagnostics =
            MutableStateFlow<ChatConnectionDiagnostics>(
                chatConnectionHub.listingDiagnostics(serverId)?.let(::mapDiagnostics) ?: ChatConnectionDiagnostics(),
            )
        val connectionDiagnostics: StateFlow<ChatConnectionDiagnostics> = _connectionDiagnostics.asStateFlow()

        private val _events = MutableSharedFlow<SessionListEvent>()
        val events = _events.asSharedFlow()

        private var pendingCreateAfterCwd = false

        private val _pendingAuthentication = MutableStateFlow<SessionListPendingAuthentication?>(null)
        val pendingAuthentication: StateFlow<SessionListPendingAuthentication?> = _pendingAuthentication.asStateFlow()
        private var refreshJob: kotlinx.coroutines.Job? = null
        private var refreshGeneration = 0

        init {
            refreshSessions()
            viewModelScope.launch {
                chatConnectionHub.warmServers.collect { warmServers ->
                    val hasWarmHub = serverId in warmServers
                    if (hasWarmHub && _isLoading.value && sessions.value.isNotEmpty()) {
                        android.util.Log.d(
                            "TSList",
                            "warm hub appeared — cancelling stale isLoading sessions=${sessions.value.size}",
                        )
                        refreshJob?.cancel()
                        _isLoading.value = false
                        _refreshing.value = false
                    }
                }
            }
        }

        /**
         * Resume-path refresh: skip when a hot chat owns the socket so the list
         * never opens a competing browser transport on the way back from chat.
         * Room already holds the last list; the warm entry keeps it live.
         */
        fun refreshSessionsIfCold() {
            if (chatConnectionHub.warmManagerFor(serverId) != null) return
            refreshSessions()
        }

        /**
         * Refreshes the session list through the hub's listing seam. The hub
         * borrows a warm chat transport when one holds the gateway slot, else
         * runs its own browser transport; both the Room write and the
         * capability gate happen inside the hub.
         *
         * @param isUserInitiated true when triggered by pull-to-refresh gesture;
         *   sets [refreshing] so the pull indicator shows only on user drag.
         */
        fun refreshSessions(isUserInitiated: Boolean = false) {
            if (refreshJob?.isActive == true && !isUserInitiated) return // coalesce overlapping cold listings
            refreshJob?.cancel()
            val generation = ++refreshGeneration
            refreshJob =
                viewModelScope.launch {
                    try {
                        val settings = sessionSettingsRepository.getSettingsBlocking(serverId)
                        val cwd = settings?.cwd?.trim()?.ifBlank { null }
                        if (isUserInitiated) _refreshing.value = true
                        _isLoading.value = true
                        when (val result = chatConnectionHub.listSessions(serverId, cwd)) {
                            is ListSessionsResult.Listed -> {
                                result.agentCapabilities?.let { _agentCapabilities.value = it }
                            }

                            is ListSessionsResult.Unsupported -> {
                                result.agentCapabilities?.let { _agentCapabilities.value = it }
                            }

                            is ListSessionsResult.AuthRequired -> {
                                handleAuthenticationRequired(result, PendingAuthAction.RefreshSessions)
                                return@launch
                            }

                            is ListSessionsResult.Failed -> {
                                _events.emit(
                                    SessionListEvent.ShowError(result.message),
                                )
                            }
                        }
                        syncHubObservables()
                    } finally {
                        if (generation == refreshGeneration) {
                            _isLoading.value = false
                            _refreshing.value = false
                        }
                    }
                }
        }

        /** Pulls the hub's last-known capability/diagnostic snapshots into local state. */
        private fun syncHubObservables() {
            chatConnectionHub.listingAgentCapabilities(serverId)?.let { _agentCapabilities.value = it }
            chatConnectionHub.listingDiagnostics(serverId)?.let { _connectionDiagnostics.value = mapDiagnostics(it) }
        }

        private fun mapDiagnostics(diagnostics: com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics) =
            ChatConnectionDiagnostics(
                serverUrl = diagnostics.serverUrl,
                pendingRequestCount = diagnostics.pendingRequestCount,
                recentErrors = diagnostics.recentErrors.map { it.message },
                lastUpdatedAtMs = diagnostics.lastUpdatedAtMs,
            )

        /**
         * Proactively cancels in-flight refresh work before opening a chat, so
         * the chat's fresh attach never races a lingering list socket for the
         * gateway's single-attach slot (the hub owns the hang-up itself).
         */
        fun onChatOpened() {
            refreshJob?.cancel()
            _isLoading.value = false
            _refreshing.value = false
        }

        override fun onCleared() {
            refreshJob?.cancel()
            chatConnectionHub.releaseListing(serverId)
        }

        /**
         * Navigates to a fresh chat. Creation happens inside the chat screen on
         * its own connection (the [NEW_SESSION_ARG] sentinel route), so the new
         * session gets its own gateway runtime instead of sharing a listing
         * transport.
         */
        fun createSession(cwd: String) {
            viewModelScope.launch {
                _events.emit(
                    SessionListEvent.NavigateToChat(
                        serverId = serverId,
                        sessionId = NEW_SESSION_ARG,
                        cwd = cwd.trim(),
                        updatedAt = System.currentTimeMillis(),
                        title = null,
                    ),
                )
            }
        }

        /**
         * Long-press close: stops the backing agent process by deleting its
         * gateway session. Routed through the hub seam, which handles both
         * tracked (hot chat) and cold Room-known sessions.
         */
        fun closeSession(sessionId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                if (chatConnectionHub.isStreaming(serverId, sessionId)) {
                    _events.emit(
                        SessionListEvent.ShowError(
                            "This session is still responding. Cancel or close it from inside the chat first.",
                        ),
                    )
                    return@launch
                }
                val target = launchableTargetRepository.getTarget(serverId)
                val endpoint =
                    when (target) {
                        is LaunchableTarget.GatewayAgent ->
                            GatewayEndpoint(
                                scheme = target.gatewaySource.scheme,
                                host = target.gatewaySource.host,
                                credential = target.gatewaySource.gatewayCredential,
                            )
                        else -> {
                            _events.emit(SessionListEvent.ShowError("Close is only available for gateway sessions."))
                            return@launch
                        }
                    }
                val gatewaySessionId =
                    sessions.value.firstOrNull { it.id == sessionId }?.gatewaySessionId
                if (gatewaySessionId == null &&
                    !chatConnectionHub.isTracked(serverId, sessionId)
                ) {
                    _events.emit(SessionListEvent.ShowError("This session has no live process to close."))
                    return@launch
                }
                runCatching {
                    chatConnectionHub.closeSession(serverId, sessionId, endpoint)
                }.onFailure { error ->
                    _events.emit(
                        SessionListEvent.ShowError(error.message ?: "Failed to close session."),
                    )
                }
            }
        }

        /**
         * Creates a new session using the current working directory filter.
         */
        fun createSessionWithCurrentCwd() {
            val normalizedCwd =
                sessionSettings.value.cwd
                    ?.trim()
                    ?.ifBlank { null } ?: return
            createSession(normalizedCwd)
        }

        /** Persists [cwd] to settings, records it in the recent list, then refreshes sessions. */
        fun updateCurrentCwd(cwd: String) {
            viewModelScope.launch {
                sessionSettingsRepository.updateCwd(serverId, cwd)
                val normalized = cwd.trim().ifBlank { "" }
                if (normalized.isNotBlank()) {
                    recentCwdStore.addCwd(serverId, normalized)
                }
                refreshSessions()
                if (pendingCreateAfterCwd) {
                    pendingCreateAfterCwd = false
                    createSessionWithCurrentCwd()
                }
            }
        }

        fun setPendingCreateAfterCwd() {
            pendingCreateAfterCwd = true
        }

        /** Removes [cwd] from the recent list without affecting the current filter. */
        fun removeRecentCwd(cwd: String) {
            viewModelScope.launch {
                recentCwdStore.removeCwd(serverId, cwd)
            }
        }

        /**
         * Completes the currently pending ACP auth challenge, then retries the
         * session action that originally failed.
         */
        fun authenticate(
            methodId: String,
            envValues: Map<String, String> = emptyMap(),
        ) {
            viewModelScope.launch {
                val pending = _pendingAuthentication.value ?: return@launch
                val method = pending.authMethods.firstOrNull { it.id == methodId } ?: return@launch

                _isLoading.value = true
                _pendingAuthentication.update { it?.copy(authErrorMessage = null) }

                // Env-based gateway auth is handled by restarting the gateway runtime with env vars.
                if (method.type == "env" && pending.gatewayRuntimeId != null) {
                    authenticateGatewayEnvVar(pending, method, envValues)
                    return@launch
                }

                // The hub routes the call to the transport that raised the
                // challenge (a borrowed warm chat transport when warm-borrowed).
                when (val result = chatConnectionHub.authenticateListing(serverId, methodId)) {
                    is AcpAuthenticateResult.Failure -> {
                        _pendingAuthentication.update { it?.copy(authErrorMessage = result.message) }
                        _isLoading.value = false
                        return@launch
                    }

                    AcpAuthenticateResult.Success -> Unit
                }

                viewModelScope.launch(Dispatchers.IO) {
                    launchableTargetRepository.updatePreferredAuthMethod(serverId, methodId)
                }
                _pendingAuthentication.value = null
                retryPendingAction(pending.pendingAction)
            }
        }

        /**
         * For manual env-var auth Ferngeist cannot inject credentials into the
         * external process, so the user restarts it outside the app and this method
         * reconnects before retrying the blocked session action.
         *
         * Reconnects to a manual ACP server after the user applied env vars.
         */
        fun reconnectPendingAuthentication() {
            viewModelScope.launch {
                val pending = _pendingAuthentication.value ?: return@launch
                val server = server.value ?: return@launch
                _isLoading.value = true
                _pendingAuthentication.update { it?.copy(authErrorMessage = null) }

                chatConnectionHub.disconnectListingTransport(serverId)

                val connected =
                    when (server) {
                        is LaunchableTarget.Manual ->
                            chatConnectionHub.connectListingTransport(
                                serverId,
                                AcpConnectionConfig(
                                    scheme = server.server.scheme,
                                    host = server.server.host,
                                    preferredAuthMethodId = server.server.preferredAuthMethodId,
                                    serverDisplayName = server.name,
                                ),
                            )

                        is LaunchableTarget.GatewayAgent -> false
                    }
                if (!connected) {
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = "Failed to reconnect to ${server.name}")
                    }
                    _isLoading.value = false
                    return@launch
                }

                val initializeResult = chatConnectionHub.initializeListingTransport(serverId)
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

        /** Dismisses the authentication dialog and clears loading state. */
        fun dismissAuthenticationPrompt() {
            _pendingAuthentication.value = null
            _isLoading.value = false
        }

        /**
         * Builds and surfaces a pending authentication model from a hub
         * listing result that hit an auth challenge.
         */
        private suspend fun handleAuthenticationRequired(
            auth: ListSessionsResult.AuthRequired,
            action: PendingAuthAction,
        ) {
            val currentServer =
                server.value
                    ?: withContext(Dispatchers.IO) { launchableTargetRepository.getTarget(serverId) }
                    ?: run {
                        _events.emit(
                            SessionListEvent.ShowError(
                                "Server was removed before authentication could complete.",
                            ),
                        )
                        _isLoading.value = false
                        return
                    }
            _pendingAuthentication.value =
                SessionListPendingAuthentication(
                    serverId = serverId,
                    serverName = currentServer.name,
                    agentName = auth.agentName,
                    authMethods = auth.authMethods,
                    preferredAuthMethodId = currentServer.preferredAuthMethodId,
                    persistedEnvValues =
                        loadPersistedEnvValues(
                            authEnvValueStore,
                            currentServer.id,
                            auth.authMethods,
                        ),
                    authErrorMessage = auth.challengeMessage,
                    gatewayRuntimeId = auth.gatewayRuntimeId,
                    pendingAction = action,
                )
            _isLoading.value = false
        }

        private data class AuthContext(
            val server: LaunchableTarget,
            val gatewaySource: com.tamimarafat.ferngeist.core.model.GatewaySource,
        )

        /**
         * Resolves the server + gateway source for an in-flight authentication,
         * deleting expired credentials. Returns null after surfacing the error.
         */
        private suspend fun resolveAuthContext(): AuthContext? {
            val currentServer =
                server.value ?: run {
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = "Server was removed before authentication could complete.")
                    }
                    _isLoading.value = false
                    return null
                }
            val gatewayTarget =
                currentServer as? LaunchableTarget.GatewayAgent ?: run {
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = "Gateway was not found for ${currentServer.name}.")
                    }
                    _isLoading.value = false
                    return null
                }
            val gatewaySource =
                resolveAuthGatewaySource(gatewayTarget) ?: return null
            return AuthContext(currentServer, gatewaySource)
        }

        /**
         * Refreshes the gateway source, deleting the stored credential when it
         * has expired. Returns null after surfacing the failure.
         */
        private suspend fun resolveAuthGatewaySource(
            gatewayTarget: LaunchableTarget.GatewayAgent,
        ): com.tamimarafat.ferngeist.core.model.GatewaySource? {
            val source =
                try {
                    withContext(Dispatchers.IO) {
                        refreshGatewaySourceIfNeeded(
                            gatewayTarget.gatewaySource,
                            gatewayRepository,
                            gatewaySourceRepository,
                        )
                    }
                } catch (_: GatewayCredentialExpiredException) {
                    withContext(Dispatchers.IO) {
                        gatewaySourceRepository.deleteGateway(gatewayTarget.gatewaySource.id)
                    }
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = "Gateway credential expired. Please pair this gateway again.")
                    }
                    _isLoading.value = false
                    return null
                }
            if (source.gatewayCredential.isBlank()) {
                _pendingAuthentication.update {
                    it?.copy(authErrorMessage = "Gateway is not paired.")
                }
                _isLoading.value = false
                return null
            }
            return source
        }

        /**
         * Restarts the gateway runtime with the env payload, then asks the hub
         * to reconnect the raising transport to the new runtime handoff.
         * Returns null after surfacing the failure.
         */
        private suspend fun restartAndReconnect(
            pending: SessionListPendingAuthentication,
            currentServer: LaunchableTarget,
            gatewaySource: com.tamimarafat.ferngeist.core.model.GatewaySource,
            method: AcpAuthMethodInfo,
            envValues: Map<String, String>,
        ): com.tamimarafat.ferngeist.gateway.GatewayConnectResponse? {
            val runtimeId =
                pending.gatewayRuntimeId ?: run {
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = "Gateway runtime context is missing for ${currentServer.name}.")
                    }
                    _isLoading.value = false
                    return null
                }
            val handoff =
                runCatching {
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
                    return null
                }

            // The transport that owns the socket is the one that raised the
            // challenge (a borrowed warm chat transport when warm-borrowed);
            // restart its transport against the new runtime handoff through
            // the hub, which retains that transport.
            chatConnectionHub.disconnectListingTransport(serverId)
            val reconnected =
                chatConnectionHub.connectListingTransport(
                    serverId,
                    AcpConnectionConfig(
                        scheme = gatewaySource.scheme,
                        host = gatewaySource.host,
                        webSocketUrl = resolveGatewayWebSocketUrl(gatewaySource, handoff),
                        webSocketBearerToken = handoff.bearerToken,
                        preferredAuthMethodId = method.id,
                        gatewayRuntimeId = handoff.runtimeId,
                        gatewaySourceId = gatewaySource.id,
                        serverDisplayName = currentServer.name,
                    ),
                )
            if (!reconnected) {
                _pendingAuthentication.update {
                    it?.copy(
                        authErrorMessage = "Failed to reconnect to ${currentServer.name}",
                        gatewayRuntimeId = handoff.runtimeId,
                    )
                }
                _isLoading.value = false
                return null
            }
            return handoff
        }

        /**
         * Handles gateway-backed env authentication by restarting the runtime with env vars.
         */
        private suspend fun authenticateGatewayEnvVar(
            pending: SessionListPendingAuthentication,
            method: AcpAuthMethodInfo,
            envValues: Map<String, String>,
        ) {
            // Gateway-backed env auth requires a fresh process environment. Restart
            // the gateway runtime with the saved values, reconnect, authenticate,
            // then retry the original session action.
            val context = resolveAuthContext() ?: return
            val currentServer = context.server
            val gatewaySource = context.gatewaySource

            persistEnvValues(authEnvValueStore, currentServer.id, method, envValues)
            val handoff =
                restartAndReconnect(
                    pending = pending,
                    currentServer = currentServer,
                    gatewaySource = gatewaySource,
                    method = method,
                    envValues = envValues,
                ) ?: return

            // Keep the whole env-auth sequence on the transport that owns the
            // socket — the same transport the hub just reconnected.
            val initializeResult = chatConnectionHub.initializeListingTransport(serverId)
            if (initializeResult == null) {
                _pendingAuthentication.update {
                    it?.copy(
                        authErrorMessage = "Failed to initialize ${currentServer.name}",
                        gatewayRuntimeId = handoff.runtimeId,
                    )
                }
                _isLoading.value = false
                return
            }

            when (val result = chatConnectionHub.authenticateListing(serverId, method.id)) {
                is AcpAuthenticateResult.Failure -> {
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = result.message, gatewayRuntimeId = handoff.runtimeId)
                    }
                    _isLoading.value = false
                    return
                }

                AcpAuthenticateResult.Success -> Unit
            }

            viewModelScope.launch(Dispatchers.IO) {
                launchableTargetRepository.updatePreferredAuthMethod(currentServer.id, method.id)
            }
            _pendingAuthentication.value = null
            retryPendingAction(pending.pendingAction)
        }

        /** Retries the action that triggered the authentication prompt. */
        private fun retryPendingAction(action: PendingAuthAction) {
            when (action) {
                PendingAuthAction.RefreshSessions -> refreshSessions()
                is PendingAuthAction.CreateSession -> createSession(action.cwd)
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

    data class ShowError(
        val message: String,
    ) : SessionListEvent
}
