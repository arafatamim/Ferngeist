package com.tamimarafat.ferngeist.feature.sessionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
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
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValuesUnreadableException
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.RecentCwdStore
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.filterSessionsByCwd
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
import kotlinx.coroutines.flow.combine
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
        private val connectivityObserver: ConnectivityObserver,
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

        /**
         * Raw cached rows, unfiltered.
         *
         * Internal callers use these to ask whether the cache holds anything at
         * all. [sessionRows] deliberately does NOT derive from them: a
         * `stateIn` seed is indistinguishable from a real empty read to a new
         * collector, so loaded-ness has to come from the cold flow itself.
         */
        private val sessions: StateFlow<List<SessionSummary>> =
            sessionRepository
                .getSessions(serverId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /**
         * The rows the list shows, plus whether the cache has actually answered
         * yet.
         *
         * Rows and [SessionRows.loaded] travel together deliberately. The
         * `sessions` StateFlow above cannot supply `loaded`: it is seeded
         * empty, so every new collector sees that seed at once and an
         * empty-state flash beats the data to the screen. Deriving both from
         * the repository's cold flow makes the first emission a real Room read,
         * and carrying them in one value keeps them from disagreeing mid-paint.
         */
        val sessionRows: StateFlow<SessionRows> =
            combine(
                sessionRepository.getSessions(serverId).map { cached -> cached to true },
                sessionSettings,
            ) { (cached, loaded), settings ->
                // Filtering locally keeps the list truthful while the agent is
                // unreachable — the hub's listing can only filter by re-asking
                // the gateway, and its result replaces this same cache.
                SessionRows(rows = filterSessionsByCwd(cached, settings.cwd), loaded = loaded)
            }.stateIn(
                viewModelScope,
                // Eagerly, not WhileSubscribed: this view model is scoped to one
                // nav entry, so every open builds a fresh stateIn whose seed is
                // an empty list. Starting only on first subscription puts the
                // whole Room round trip after the first frame, which shows up as
                // a blank screen mid-transition. Subscribing at construction
                // gives the query the navigation animation to land in, and Room's
                // Flow is an invalidation observer, so idling here costs nothing.
                SharingStarted.Eagerly,
                SessionRows(rows = emptyList(), loaded = false),
            )

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

        /** True when the last listing attempt never reached the agent. */
        private var lastListingFailed = false

        init {
            refreshSessions()
            viewModelScope.launch {
                // A listing that failed while the network was down is stale the
                // moment the network returns: nothing else re-runs it, so the
                // locally filtered cache would keep standing in for the agent's
                // own answer. One retry per network transition — a listing that
                // fails again leaves the flag set but no new transition to fire on.
                connectivityObserver.isConnected.collect { connected ->
                    if (connected && lastListingFailed) {
                        refreshSessions()
                    }
                }
            }
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
         * Resume-path refresh. Skips the network listing when cached rows exist
         * and the last listing succeeded — the hub still repaints instantly
         * from Room, so a resume with warm cache needs no refetch.
         */
        fun refreshSessionsIfCold() {
            if (sessions.value.isNotEmpty() && !lastListingFailed) return
            refreshSessions()
        }

        /**
         * Resolves the gateway REST endpoint for [serverId], or null for
         * non-gateway targets (manual agents have no gateway leg).
         */
        private suspend fun gatewayEndpoint(): GatewayEndpoint? =
            when (val target = launchableTargetRepository.getTarget(serverId)) {
                is LaunchableTarget.GatewayAgent -> gatewayEndpoint(target)
                else -> null
            }

        private fun gatewayEndpoint(target: LaunchableTarget.GatewayAgent): GatewayEndpoint =
            GatewayEndpoint(
                scheme = target.gatewaySource.scheme,
                host = target.gatewaySource.host,
                credential = target.gatewaySource.gatewayCredential,
            )

        /**
         * Drops gateway leases the gateway forgot while the app was away, so
         * the list stops offering disconnects that can only fail. Only when
         * the transport worked — a failed listing means the gateway is likely
         * unreachable too, so a second call could only fail. Skipped for
         * non-gateway targets and on auth challenges (which return before
         * reaching here).
         */
        private suspend fun reconcileGatewayLeases(result: ListSessionsResult) {
            if (result !is ListSessionsResult.Listed && result !is ListSessionsResult.Unsupported) return
            gatewayEndpoint()?.let { endpoint ->
                chatConnectionHub.reconcileGatewaySessions(serverId, endpoint)
            }
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
                        val result = chatConnectionHub.listSessions(serverId, cwd)
                        when (result) {
                            is ListSessionsResult.Listed -> {
                                lastListingFailed = false
                                result.agentCapabilities?.let { _agentCapabilities.value = it }
                            }

                            is ListSessionsResult.Unsupported -> {
                                lastListingFailed = false
                                result.agentCapabilities?.let { _agentCapabilities.value = it }
                            }

                            is ListSessionsResult.AuthRequired -> {
                                lastListingFailed = false
                                handleAuthenticationRequired(result, PendingAuthAction.RefreshSessions)
                                return@launch
                            }

                            is ListSessionsResult.Failed -> {
                                lastListingFailed = true
                                // Silent on an auto resume refresh with cached rows; a
                                // warm socket mid-reconnect would otherwise toast on
                                // every return from chat. Still loud for a pull-to-refresh
                                // or when the user is looking at nothing.
                                if (isUserInitiated || sessions.value.isEmpty()) {
                                    _events.emit(
                                        SessionListEvent.ShowError(result.message),
                                    )
                                }
                            }
                        }
                        // Paint first, then reconcile leases in the background
                        // (details in publishListingAndReconcile).
                        publishListingAndReconcile(result, generation)
                    } finally {
                        if (generation == refreshGeneration) {
                            _isLoading.value = false
                            _refreshing.value = false
                        }
                    }
                }
        }

        /**
         * Publishes hub snapshots and clears the loading flags for first
         * paint, then reconciles stale gateway leases in the background: the
         * reconcile's up-to-10s REST window must never extend the spinner. It
         * only prunes Room rows, which re-emit when they land.
         */
        private fun publishListingAndReconcile(
            result: ListSessionsResult,
            generation: Int,
        ) {
            syncHubObservables()
            if (generation == refreshGeneration) {
                _isLoading.value = false
                _refreshing.value = false
            }
            viewModelScope.launch {
                reconcileGatewayLeases(result)
                if (generation == refreshGeneration) syncHubObservables()
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
                        is LaunchableTarget.GatewayAgent -> gatewayEndpoint(target)
                        else -> {
                            _events.emit(SessionListEvent.ShowError("Close is only available for gateway sessions."))
                            return@launch
                        }
                    }
                runCatching {
                    chatConnectionHub.closeSession(serverId, sessionId, endpoint)
                }.onSuccess { closed ->
                    if (!closed) {
                        _events.emit(SessionListEvent.ShowError("This session has no live process to close."))
                    }
                }.onFailure { error ->
                    _events.emit(
                        SessionListEvent.ShowError(error.message ?: "Failed to close session."),
                    )
                }
            }
        }

        /**
         * Long-press delete: best-effort ACP `session/delete` when the agent
         * advertises it, gateway DELETE when a process is attached, then the
         * local row. Unlike [closeSession] a non-gateway target is not an
         * error — there is simply no remote leg to run.
         */
        fun deleteSession(sessionId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                if (chatConnectionHub.isStreaming(serverId, sessionId)) {
                    _events.emit(
                        SessionListEvent.ShowError(
                            "This session is still responding. Cancel or close it from inside the chat first.",
                        ),
                    )
                    return@launch
                }
                val endpoint = gatewayEndpoint()
                runCatching {
                    chatConnectionHub.deleteSession(serverId, sessionId, endpoint)
                }.onSuccess { deleted ->
                    if (!deleted) {
                        _events.emit(SessionListEvent.ShowError("This session could not be found."))
                    }
                }.onFailure { error ->
                    _events.emit(
                        SessionListEvent.ShowError(error.message ?: "Failed to delete session."),
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

        /**
         * Persists [cwd] to settings, records it in the recent list, then
         * refreshes sessions. The refresh counts as user-initiated: the list
         * already narrows to [cwd] locally, so a listing the agent could not
         * answer must say so instead of passing for an empty directory.
         */
        fun updateCurrentCwd(cwd: String) {
            viewModelScope.launch {
                sessionSettingsRepository.updateCwd(serverId, cwd)
                val normalized = cwd.trim().ifBlank { "" }
                if (normalized.isNotBlank()) {
                    recentCwdStore.addCwd(serverId, normalized)
                }
                refreshSessions(isUserInitiated = true)
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
         *
         * Each failure path exits early — no auth context, an unreadable stored copy, a failed
         * handoff — so the exit count is inherent to the sequence rather than a sign of nested
         * conditions that want flattening.
         */
        @Suppress("ReturnCount")
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

            try {
                persistEnvValues(authEnvValueStore, currentServer.id, method, envValues)
            } catch (error: AuthEnvValuesUnreadableException) {
                // The stored copy decrypts but cannot be parsed, so this write's base was an
                // empty map. Continuing would destroy secrets the user still has; the stored
                // blob is left untouched for them to replace.
                _events.emit(
                    SessionListEvent.ShowError(
                        error.message ?: "Saved environment values could not be read on this device.",
                    ),
                )
                return
            }
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

/**
 * Session rows and the answer to "has the cache answered yet?".
 *
 * [loaded] is false only before the cache's first real emission. It exists
 * because a list seeded empty cannot distinguish "nothing stored" from "not
 * read yet", and that difference is a visible flash of the wrong screen.
 */
data class SessionRows(
    val rows: List<SessionSummary>,
    val loaded: Boolean,
)
