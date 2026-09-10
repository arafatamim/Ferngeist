package com.tamimarafat.ferngeist.feature.sessionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
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
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import com.tamimarafat.ferngeist.feature.serverlist.buildGatewayLaunchContext
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
    /**
     * The transport that surfaced the challenge. When the session list
     * borrowed a warm chat manager, that manager owns the gateway socket —
     * authentication must run through it, never this screen's idle browser
     * [AcpConnectionManager]. Null means the browser manager raised it.
     */
    val authManager: AcpConnectionManager? = null,
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
 * Converts ACP transport state into chat-domain diagnostics for UI rendering.
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
        private val connectionManager: AcpConnectionManager = chatConnectionHub.createBrowserManager(viewModelScope)
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
                    // transports. The screen's own browser socket hangs up
                    // after every listing, so it must not drive the pill.
                    if (warm) ChatConnectionState.Connected else ChatConnectionState.Disconnected
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatConnectionState.Disconnected)
        val agentCapabilities: StateFlow<AgentCapabilities?> =
            connectionManager.agentCapabilities
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        val connectionDiagnostics: StateFlow<ChatConnectionDiagnostics> =
            connectionManager.diagnostics
                .map { diagnostics ->
                    ChatConnectionDiagnostics(
                        serverUrl = diagnostics.serverUrl,
                        pendingRequestCount = diagnostics.pendingRequestCount,
                        recentErrors = diagnostics.recentErrors.map { it.message },
                        lastUpdatedAtMs = diagnostics.lastUpdatedAtMs,
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatConnectionDiagnostics())

        private val _events = MutableSharedFlow<SessionListEvent>()
        val events = _events.asSharedFlow()

        private var pendingCreateAfterCwd = false

        private val _pendingAuthentication = MutableStateFlow<SessionListPendingAuthentication?>(null)
        val pendingAuthentication: StateFlow<SessionListPendingAuthentication?> = _pendingAuthentication.asStateFlow()
        private var refreshJob: kotlinx.coroutines.Job? = null

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
            if (findWarmChatManager() != null) return
            refreshSessions()
        }

        /**
         * Refreshes the session list, handling auth gating and capability checks.
         *
         * When a hub-tracked chat owns the gateway's single-attach slot, the
         * list is fetched through that warm transport; otherwise the screen's
         * own browser transport connects, lists, then hangs up.
         *
         * @param isUserInitiated true when triggered by pull-to-refresh gesture;
         *   sets [refreshing] so the pull indicator shows only on user drag.
         */
        fun refreshSessions(isUserInitiated: Boolean = false) {
            refreshJob?.cancel()
            refreshJob =
                viewModelScope.launch {
                    try {
                        val settings = sessionSettingsRepository.getSettingsBlocking(serverId)
                        val cwd = settings?.cwd?.trim()?.ifBlank { null }
                        val warm = findWarmChatManager()
                        if (warm != null) {
                            // A hot chat owns the gateway's single-attach slot:
                            // borrow it so the list never competes — and leave our
                            // browser socket DOWN, else its reconnect evicts the
                            // chat socket right after this refresh succeeds.
                            hangUpBrowserSocket()
                            listViaWarmChat(warm, cwd, isUserInitiated)
                        } else {
                            listViaBrowser(cwd, isUserInitiated)
                        }
                    } finally {
                        _isLoading.value = false
                        _refreshing.value = false
                    }
                }
        }

        /**
         * Lists sessions through [warm], a hub-tracked chat transport that owns
         * the gateway's single-attach slot while a chat is open. The caller has
         * already dropped this screen's browser socket so it cannot evict the
         * chat. Never disconnects [warm] — the chat owns it.
         */
        @OptIn(UnstableApi::class)
        private suspend fun listViaWarmChat(
            warm: AcpConnectionManager,
            cwd: String?,
            isUserInitiated: Boolean,
        ) {
            val warmCapabilities = warm.agentCapabilities.value
            if (warmCapabilities != null && warmCapabilities.sessionCapabilities.list == null) return
            if (isUserInitiated) _refreshing.value = true
            _isLoading.value = true
            runCatching { warm.listSessions(cwd = cwd) }
                .onSuccess { remoteSessions ->
                    sessionRepository.replaceSessions(serverId, remoteSessions)
                }.onFailure { error ->
                    val needsAuth =
                        handleAuthenticationRequired(
                            error,
                            PendingAuthAction.RefreshSessions,
                            warm,
                        )
                    if (needsAuth) return
                    _events.emit(
                        SessionListEvent.ShowError(
                            formatAcpErrorMessage(error, "Failed to load sessions"),
                        ),
                    )
                }
        }

        /**
         * Lists sessions over this screen's own browser transport, connecting
         * on demand. Hangs the socket back up afterwards: an idle browser
         * socket's reconnect loop would steal the gateway's single-attach slot
         * from a chat opened next.
         */
        @OptIn(UnstableApi::class)
        private suspend fun listViaBrowser(
            cwd: String?,
            isUserInitiated: Boolean,
        ) {
            if (!connectIfNeeded()) return
            if (!connectionManager.isConnected) return
            val capabilities = connectionManager.agentCapabilities.value
            if (capabilities != null && capabilities.sessionCapabilities.list == null) return
            if (isUserInitiated) _refreshing.value = true
            _isLoading.value = true
            runCatching { connectionManager.listSessions(cwd = cwd) }
                .onSuccess { remoteSessions ->
                    sessionRepository.replaceSessions(serverId, remoteSessions)
                }.onFailure { error ->
                    val needsAuth =
                        handleAuthenticationRequired(error, PendingAuthAction.RefreshSessions)
                    if (needsAuth) {
                        hangUpBrowserSocket()
                        return
                    }
                    _events.emit(
                        SessionListEvent.ShowError(
                            formatAcpErrorMessage(error, "Failed to load sessions"),
                        ),
                    )
                }
            // Idle browser socket's reconnect would steal the gateway's single-attach slot.
            hangUpBrowserSocket()
        }

        /**
         * A hub-tracked chat transport already connected for this server, or
         * null. Listing through it avoids stealing the gateway's single-attach
         * socket from a live chat. Never disconnect the returned manager.
         */
        private fun findWarmChatManager(): AcpConnectionManager? = chatConnectionHub.warmManagerFor(serverId)

        /**
         * Drops this screen's browser socket if it is up. An idle browser
         * socket's reconnect loop would steal the gateway's single-attach slot
         * from a chat opened next, so listing surfaces hang up after use.
         */
        private suspend fun hangUpBrowserSocket() {
            if (connectionManager.isConnected) {
                withContext(Dispatchers.IO) { connectionManager.disconnect() }
            }
        }

        /**
         * Proactively hangs up the browser socket before opening a chat, so the
         * chat's fresh attach never races a lingering list socket for the
         * gateway's single-attach slot.
         */
        fun onChatOpened() {
            refreshJob?.cancel()
            _isLoading.value = false
            _refreshing.value = false
            if (connectionManager.isConnected) {
                viewModelScope.launch { hangUpBrowserSocket() }
            }
        }

        override fun onCleared() {
            refreshJob?.cancel()
            connectionManager.disconnect()
            super.onCleared()
        }

        /**
         * Navigates to a fresh chat. Creation happens inside the chat screen on
         * its own connection (the [NEW_SESSION_ARG] sentinel route), so the new
         * session gets its own gateway runtime instead of sharing the browser
         * transport used for listing.
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
         * gateway session. Hot chats go through the hub; cold chats (tracked
         * only via the persisted [SessionSummary.gatewaySessionId]) call the
         * gateway directly.
         */
        fun closeSession(sessionId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                val gatewaySessionId =
                    sessions.value.firstOrNull { it.id == sessionId }?.gatewaySessionId
                if (gatewaySessionId == null) {
                    _events.emit(SessionListEvent.ShowError("This session has no live process to close."))
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
                val chatId = "$serverId/$sessionId"
                runCatching {
                    if (chatConnectionHub.isTracked(serverId, sessionId)) {
                        chatConnectionHub.close(chatId, endpoint)
                        sessionRepository.setGatewaySessionId(serverId, sessionId, null)
                    } else {
                        gatewayRepository.closeSession(
                            endpoint.scheme,
                            endpoint.host,
                            endpoint.credential,
                            gatewaySessionId,
                        )
                        sessionRepository.setGatewaySessionId(serverId, sessionId, null)
                    }
                }.onFailure { error ->
                    _events.emit(
                        SessionListEvent.ShowError(error.message ?: "Failed to close session."),
                    )
                }
            }
        }

        /**
         * Ensures the ACP transport is connected before a session operation,
         * reconnecting when needed. Emits a clear error when reconnection fails.
         *
         * @return true when connected (or already connected); false stops the caller.
         */
        private suspend fun connectIfNeeded(): Boolean {
            if (connectionManager.isConnected) return true

            // One-shot lookup: server.value is still null on first launch
            // before Room emits, which falsely reported "Server was removed".
            val target =
                launchableTargetRepository.getTarget(serverId)
                    ?: return showConnectError("Server was removed before connecting.")
            val config = buildConnectionConfig(target) ?: return false

            val initialized =
                withContext(Dispatchers.IO) {
                    connectionManager.connectAndInitializeWithoutReconnect(config)
                }
            return if (initialized == null) {
                showConnectError(
                    "Failed to connect to ${target.name}. Check your connection and try again.",
                )
            } else {
                true
            }
        }

        /**
         * Builds the transport config for [target], starting the gateway runtime
         * when needed. Emits a clear error and returns null on failure.
         */
        private suspend fun buildConnectionConfig(target: LaunchableTarget): AcpConnectionConfig? {
            val launchContext =
                when (target) {
                    is LaunchableTarget.GatewayAgent ->
                        buildGatewayLaunchContext(gatewayRepository, gatewaySourceRepository, target)
                            .getOrElse { error ->
                                showConnectError(error.message ?: "Failed to launch ${target.name}")
                                return null
                            }
                    is LaunchableTarget.Manual -> null
                }
            return launchContext?.config
                ?: (target as? LaunchableTarget.Manual)?.let { manual ->
                    AcpConnectionConfig(
                        scheme = manual.server.scheme,
                        host = manual.server.host,
                        preferredAuthMethodId = manual.server.preferredAuthMethodId,
                        serverDisplayName = manual.name,
                    )
                }
                ?: run {
                    showConnectError("Unknown server type.")
                    null
                }
        }

        /** Emits a connection error to the UI and returns false to stop the caller. */
        private suspend fun showConnectError(message: String): Boolean {
            _events.emit(SessionListEvent.ShowError(message))
            return false
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

                // The challenge may have surfaced on a borrowed warm chat
                // manager that owns the live socket; authenticate there, not on
                // this screen's browser transport, which is disconnected while
                // a warm chat holds the gateway's single-attach slot.
                val authManager = pending.authManager ?: connectionManager
                when (val result = authManager.authenticate(methodId)) {
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

                withContext(Dispatchers.IO) {
                    connectionManager.disconnect()
                }

                val connected =
                    withContext(Dispatchers.IO) {
                        when (server) {
                            is LaunchableTarget.Manual -> {
                                connectionManager.connect(
                                    AcpConnectionConfig(
                                        scheme = server.server.scheme,
                                        host = server.server.host,
                                        preferredAuthMethodId = server.server.preferredAuthMethodId,
                                        serverDisplayName = server.name,
                                    ),
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

                val initializeResult =
                    withContext(Dispatchers.IO) {
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

        /** Dismisses the authentication dialog and clears loading state. */
        fun dismissAuthenticationPrompt() {
            _pendingAuthentication.value = null
            _isLoading.value = false
        }

        /**
         * Builds and surfaces a pending authentication model when ACP requires auth.
         */
        private suspend fun handleAuthenticationRequired(
            error: Throwable,
            action: PendingAuthAction,
            listingManager: AcpConnectionManager = connectionManager,
        ): Boolean {
            // ACP auth is session-gated. initialize() only advertises methods; the first
            // session action failure is what opens the authentication prompt.
            val authError = error as? AcpAuthenticationRequiredException ?: return false
            val currentServer = server.value ?: return false
            _pendingAuthentication.value =
                SessionListPendingAuthentication(
                    serverId = serverId,
                    serverName = currentServer.name,
                    agentName = authError.challenge.agentInfo.name,
                    authMethods = authError.challenge.authMethods,
                    preferredAuthMethodId = currentServer.preferredAuthMethodId,
                    persistedEnvValues = loadPersistedEnvValues(currentServer.id, authError.challenge.authMethods),
                    authErrorMessage = authError.challenge.message,
                    gatewayRuntimeId = listingManager.currentConnectionConfig()?.gatewayRuntimeId,
                    pendingAction = action,
                    authManager = listingManager,
                )
            _isLoading.value = false
            return true
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
         * Restarts the gateway runtime with the env payload, disconnects the old
         * transport, and reconnects with the new handoff. Returns null after
         * surfacing the failure.
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

            // The manager that owns the socket is the one that raised the
            // challenge (a borrowed warm chat transport when warm-borrowed);
            // restart its transport against the new runtime handoff.
            val transport = pending.authManager ?: connectionManager
            withContext(Dispatchers.IO) {
                transport.disconnect()
            }

            val reconnected =
                withContext(Dispatchers.IO) {
                    transport.connect(
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
                }
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

            persistEnvValues(currentServer.id, method, envValues)
            val handoff =
                restartAndReconnect(
                    pending = pending,
                    currentServer = currentServer,
                    gatewaySource = gatewaySource,
                    method = method,
                    envValues = envValues,
                ) ?: return

            // Keep the whole env-auth sequence on the transport that owns the
            // socket — the same manager restartAndReconnect just reconnected.
            val transport = pending.authManager ?: connectionManager
            val initializeResult =
                withContext(Dispatchers.IO) {
                    transport.initialize()
                }
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

            when (val result = transport.authenticate(method.id)) {
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

        /**
         * Loads persisted env vars, limited to the auth methods currently requested.
         */
        private suspend fun loadPersistedEnvValues(
            serverId: String,
            authMethods: List<AcpAuthMethodInfo>,
        ): Map<String, String> {
            val allowedNames =
                authMethods
                    .flatMap { method -> method.envVars }
                    .mapTo(linkedSetOf()) { envVar -> envVar.name }
            if (allowedNames.isEmpty()) {
                return emptyMap()
            }
            return withContext(Dispatchers.IO) {
                authEnvValueStore
                    .getValues(serverId)
                    .filterKeys { key -> key in allowedNames }
            }
        }

        /**
         * Persists env vars for the selected auth method so the dialog can be prefilled.
         */
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

        /**
         * Builds the env payload for gateway restart, omitting blank optional fields.
         */
        private fun buildEnvPayload(
            method: AcpAuthMethodInfo,
            envValues: Map<String, String>,
        ): Map<String, String> {
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
