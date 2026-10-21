package com.tamimarafat.ferngeist.feature.sessionlist

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.catalog.CatalogAuthMethod
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog.ListSessionsResult
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog.ListingAuthentication
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
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValuesUnreadableException
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.RecentCwdStore
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.chatsInWorktree
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.filterSessionsByCwd
import com.tamimarafat.ferngeist.feature.sessionlist.worktree.SessionWorktrees
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val authMethods: List<CatalogAuthMethod>,
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
 * Room session-table write) lives behind [SessionCatalog]'s listing seam;
 * this VM only maps results onto UI state, drives the auth dialogs, and keeps
 * env-value persistence. It never holds a transport.
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
        @ApplicationContext private val context: Context,
        savedStateHandle: SavedStateHandle,
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val launchableTargetRepository: LaunchableTargetRepository,
        private val sessionRepository: SessionRepository,
        private val gatewayRepository: GatewayRepository,
        private val sessionCatalog: SessionCatalog,
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
            sessionCatalog
                .connectedSessionIds(serverId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        // NOTE: Separate from _isLoading so PullToRefreshDefaults.LoadingIndicator
        // only shows on user-pull, not on initial load with cached sessions.
        private val _refreshing = MutableStateFlow(false)
        val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
        val connectionState: StateFlow<ChatConnectionState> =
            sessionCatalog.warmServers
                .map { warmServers ->
                    val warm = serverId in warmServers
                    // One source of truth: hub-tracked chats holding live
                    // transports. Listing transports hang up after every list, so
                    // they must not drive the pill.
                    if (warm) ChatConnectionState.Connected else ChatConnectionState.Disconnected
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatConnectionState.Disconnected)

        /** Last-known agent capabilities for this server (from catalog listings). */
        private val _agentCapabilities =
            MutableStateFlow<AgentCapabilities?>(
                sessionCatalog.agentCapabilities(serverId),
            )
        val agentCapabilities: StateFlow<AgentCapabilities?> = _agentCapabilities.asStateFlow()

        /** Last-known transport diagnostics for this server (from catalog listings). */
        private val _connectionDiagnostics =
            MutableStateFlow<ChatConnectionDiagnostics>(
                sessionCatalog.listingDiagnostics(serverId) ?: ChatConnectionDiagnostics(),
            )
        val connectionDiagnostics: StateFlow<ChatConnectionDiagnostics> = _connectionDiagnostics.asStateFlow()

        private val _events = MutableSharedFlow<SessionListEvent>()
        val events = _events.asSharedFlow()

        private var pendingCreateAfterCwd = false

        /**
         * Gateway worktrees for this list: creating one for a new chat, listing the gateway's
         * managed ones, and removing them. A separate type so this view model stays a
         * coordinator rather than growing its own gateway client.
         */
        private val sessionWorktrees =
            SessionWorktrees(
                context = context,
                serverId = serverId,
                gatewayRepository = gatewayRepository,
                gatewaySourceRepository = gatewaySourceRepository,
                launchableTargetRepository = launchableTargetRepository,
                sessionSettingsRepository = sessionSettingsRepository,
                recentCwdStore = recentCwdStore,
                emitEvent = { _events.emit(it) },
                openChat = { createSession(it) },
            )

        /** Managed worktrees, or null when this gateway has none to show (older gateway). */
        val worktrees: StateFlow<List<GatewayWorktree>?> = sessionWorktrees.managed

        /** True while a worktree create is in flight; the dialog's confirm button stays busy. */
        val creatingWorktree: StateFlow<Boolean> = sessionWorktrees.creating

        /** Inline error for the worktree form (bad repo/branch, existing branch), else null. */
        val worktreeError: StateFlow<String?> = sessionWorktrees.error

        private val _removingWorktreeIds = MutableStateFlow<Set<String>>(emptySet())

        /** Ids of worktrees whose removal is in flight; their chat cards show as busy. */
        val removingWorktreeIds: StateFlow<Set<String>> = _removingWorktreeIds.asStateFlow()

        private val _pendingAuthentication = MutableStateFlow<SessionListPendingAuthentication?>(null)
        val pendingAuthentication: StateFlow<SessionListPendingAuthentication?> = _pendingAuthentication.asStateFlow()
        private var refreshJob: kotlinx.coroutines.Job? = null
        private var refreshGeneration = 0
        private var worktreeRefreshJob: kotlinx.coroutines.Job? = null

        /** True when the last listing attempt never reached the agent. */
        private var lastListingFailed = false

        init {
            // The server list's launch has usually just listed over its own connection.
            refreshSessions(reuseRecent = true)
            viewModelScope.launch {
                // A listing that failed while the network was down is stale the
                // moment the network returns: nothing else re-runs it, so the
                // locally filtered cache would keep standing in for the agent's
                // own answer. One retry per network transition — a listing that
                // fails again leaves the flag set but no new transition to fire on.
                sessionCatalog.networkAvailable.collect { connected ->
                    if (connected && lastListingFailed) {
                        refreshSessions()
                    }
                }
            }
            viewModelScope.launch {
                sessionCatalog.warmServers.collect { warmServers ->
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
         * Drops gateway leases the gateway forgot while the app was away, so
         * the list stops offering disconnects that can only fail. Only when
         * the transport worked — a failed listing means the gateway is likely
         * unreachable too, so a second call could only fail. Skipped for
         * non-gateway targets and on auth challenges (which return before
         * reaching here).
         */
        private suspend fun reconcileGatewayLeases(result: ListSessionsResult) {
            if (result !is ListSessionsResult.Listed && result !is ListSessionsResult.Unsupported) return
            sessionCatalog.reconcileGatewayLeases(serverId)
        }

        /**
         * Refreshes the session list through the catalog's listing seam. It
         * borrows a warm chat transport when one holds the gateway slot, else
         * runs its own browser transport; both the Room write and the
         * capability gate happen inside the catalog.
         *
         * @param isUserInitiated true when triggered by pull-to-refresh gesture;
         *   sets [refreshing] so the pull indicator shows only on user drag.
         * @param reuseRecent true to accept a listing the catalog finished moments ago instead
         *   of launching the agent again.
         */
        fun refreshSessions(
            isUserInitiated: Boolean = false,
            reuseRecent: Boolean = false,
        ) {
            if (refreshJob?.isActive == true && !isUserInitiated) return // coalesce overlapping cold listings
            refreshJob?.cancel()
            val generation = ++refreshGeneration
            refreshJob =
                viewModelScope.launch {
                    try {
                        if (isUserInitiated) _refreshing.value = true
                        _isLoading.value = true
                        // Always the whole list: the cache keeps only what a listing returns,
                        // and an agent filtering by cwd matches it exactly, so a filtered
                        // listing would drop the repo's worktree chats (and every other
                        // directory). The cwd filter is applied locally by sessionRows.
                        val result = sessionCatalog.listSessions(serverId, cwd = null, reuseRecent = reuseRecent)
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
                                val alreadyFailing = lastListingFailed
                                lastListingFailed = true
                                // Silent on an auto refresh with cached rows; a warm socket
                                // mid-reconnect would otherwise toast on every return from
                                // chat. Still loud for a pull-to-refresh, or once when the
                                // user is looking at nothing (not on every poll after it).
                                if (isUserInitiated || (sessions.value.isEmpty() && !alreadyFailing)) {
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
         * Publishes catalog snapshots and clears the loading flags for first
         * paint, then reconciles stale gateway leases in the background: the
         * reconcile's up-to-10s REST window must never extend the spinner. It
         * only prunes Room rows, which re-emit when they land.
         */
        private fun publishListingAndReconcile(
            result: ListSessionsResult,
            generation: Int,
        ) {
            syncCatalogObservables()
            if (generation == refreshGeneration) {
                _isLoading.value = false
                _refreshing.value = false
            }
            viewModelScope.launch {
                reconcileGatewayLeases(result)
                sessionWorktrees.refresh()
                if (generation == refreshGeneration) syncCatalogObservables()
            }
        }

        /**
         * Re-reads the gateway's worktrees (ahead/dirty) on its own, without waiting for the
         * session listing and lease reconcile that normally precede it. One REST call, dropped
         * while the previous one is still in flight.
         */
        fun refreshWorktrees() {
            if (worktreeRefreshJob?.isActive == true) return
            worktreeRefreshJob = viewModelScope.launch { sessionWorktrees.refresh() }
        }

        /** Pulls the catalog's last-known capability/diagnostic snapshots into local state. */
        private fun syncCatalogObservables() {
            sessionCatalog.agentCapabilities(serverId)?.let { _agentCapabilities.value = it }
            sessionCatalog.listingDiagnostics(serverId)?.let { _connectionDiagnostics.value = it }
        }

        /**
         * Proactively cancels in-flight refresh work before opening a chat, so
         * the chat's fresh attach never races a lingering list socket for the
         * gateway's single-attach slot (the catalog owns the hang-up itself).
         */
        fun onChatOpened() {
            refreshJob?.cancel()
            _isLoading.value = false
            _refreshing.value = false
        }

        override fun onCleared() {
            refreshJob?.cancel()
            sessionCatalog.releaseListing(serverId)
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
         * gateway session. Routed through the catalog seam, which handles both
         * tracked (hot chat) and cold Room-known sessions.
         */
        fun closeSession(sessionId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                if (sessionCatalog.isStreaming(serverId, sessionId)) {
                    _events.emit(
                        SessionListEvent.ShowError(
                            "This session is still responding. Cancel or close it from inside the chat first.",
                        ),
                    )
                    return@launch
                }
                val target = launchableTargetRepository.getTarget(serverId)
                if (target !is LaunchableTarget.GatewayAgent) {
                    _events.emit(SessionListEvent.ShowError("Close is only available for gateway sessions."))
                    return@launch
                }
                runCatching {
                    sessionCatalog.closeSession(serverId, sessionId)
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
                if (sessionCatalog.isStreaming(serverId, sessionId)) {
                    _events.emit(
                        SessionListEvent.ShowError(
                            "This session is still responding. Cancel or close it from inside the chat first.",
                        ),
                    )
                    return@launch
                }
                runCatching {
                    sessionCatalog.deleteSession(serverId, sessionId)
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

        /** Clears the inline worktree error as soon as the form changes. */
        fun clearWorktreeError() {
            sessionWorktrees.clearError()
        }

        /** Creates a gateway worktree for [repo] and opens a chat in it. */
        fun createSessionInNewWorktree(
            repo: String,
            base: String?,
            branch: String?,
        ) {
            // The dialog may have been opened to create a chat; this call now owns that.
            pendingCreateAfterCwd = false
            viewModelScope.launch { sessionWorktrees.create(repo, base, branch) }
        }

        /** Opens a new chat in an existing gateway worktree. */
        fun createSessionInWorktree(worktree: GatewayWorktree) {
            pendingCreateAfterCwd = false
            viewModelScope.launch { sessionWorktrees.open(worktree) }
        }

        /**
         * Asks the user to confirm removing the worktree a session's chat runs in, naming the
         * chats that go with it. Removal deletes every one of them, so it never runs unconfirmed.
         * A worktree the listing already shows as dirty goes straight to the discard confirm.
         */
        fun requestRemoveWorktree(
            sessionId: String,
            worktreeId: String,
        ) {
            val worktree = worktrees.value?.firstOrNull { it.id == worktreeId } ?: return
            viewModelScope.launch {
                _events.emit(
                    SessionListEvent.ConfirmRemoveWorktree(
                        sessionId = sessionId,
                        worktreeId = worktreeId,
                        branch = worktree.branch,
                        chatCount = chatsInWorktree(sessions.value, sessionId, worktree.path).size,
                        discardChanges = worktree.dirty == true,
                    ),
                )
            }
        }

        /**
         * Removes the worktree the user confirmed in [request].
         *
         * Every chat that ran in the worktree is deleted with it: its cwd no longer exists, so
         * the agent can only fail to load it. Each one's agent process holds the directory as
         * its cwd, which on Windows blocks the delete, so all of them are closed first — and a
         * chat still responding would lose its directory mid-turn, so that refuses the removal.
         * A 409 comes back as a discard confirm; the caller retries with it.
         */
        fun removeWorktree(request: SessionListEvent.ConfirmRemoveWorktree) {
            viewModelScope.launch(Dispatchers.IO) {
                _removingWorktreeIds.update { it + request.worktreeId }
                try {
                    val path = worktrees.value?.firstOrNull { it.id == request.worktreeId }?.path
                    val chats = chatsInWorktree(sessions.value, request.sessionId, path)
                    if (chats.any { sessionCatalog.isStreaming(serverId, it) }) {
                        _events.emit(
                            SessionListEvent.ShowError(
                                context.getString(R.string.sessionlist_worktree_error_chat_busy),
                            ),
                        )
                        return@launch
                    }
                    if (launchableTargetRepository.getTarget(serverId) !is LaunchableTarget.GatewayAgent) {
                        _events.emit(
                            SessionListEvent.ShowError(
                                context.getString(R.string.sessionlist_worktree_error_remove_gateway_only),
                            ),
                        )
                        return@launch
                    }
                    for (chat in chats) {
                        runCatching { sessionCatalog.closeSession(serverId, chat) }
                    }
                    if (!sessionWorktrees.remove(request)) return@launch
                    for (chat in chats) {
                        runCatching { sessionCatalog.deleteSession(serverId, chat) }
                    }
                } finally {
                    _removingWorktreeIds.update { it - request.worktreeId }
                }
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

                // The catalog routes the call to the transport that raised the
                // challenge (a borrowed warm chat transport when warm-borrowed).
                when (val result = sessionCatalog.authenticateListing(serverId, methodId)) {
                    is ListingAuthentication.Failed -> {
                        _pendingAuthentication.update { it?.copy(authErrorMessage = result.message) }
                        _isLoading.value = false
                        return@launch
                    }

                    ListingAuthentication.Succeeded -> Unit
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

                val connected =
                    when (server) {
                        is LaunchableTarget.Manual ->
                            sessionCatalog.reconnectListingToManual(serverId, server)

                        is LaunchableTarget.GatewayAgent -> false
                    }
                if (!connected) {
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = "Failed to reconnect to ${server.name}")
                    }
                    _isLoading.value = false
                    return@launch
                }

                if (!sessionCatalog.initializeListing(serverId)) {
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
                    persistedEnvValues = sessionCatalog.persistedEnvValues(currentServer.id, auth.authMethods),
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
         * to reconnect the raising transport to the restarted runtime.
         * Returns the new runtime id the restart launched, or null after
         * surfacing the failure.
         */
        private suspend fun restartAndReconnect(
            pending: SessionListPendingAuthentication,
            currentServer: LaunchableTarget,
            gatewaySource: com.tamimarafat.ferngeist.core.model.GatewaySource,
            method: CatalogAuthMethod,
            envValues: Map<String, String>,
        ): String? {
            val runtimeId =
                pending.gatewayRuntimeId ?: run {
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = "Gateway runtime context is missing for ${currentServer.name}.")
                    }
                    _isLoading.value = false
                    return null
                }
            val restart =
                runCatching {
                    withContext(Dispatchers.IO) {
                        gatewayRepository.restartRuntime(
                            scheme = gatewaySource.scheme,
                            host = gatewaySource.host,
                            gatewayCredential = gatewaySource.gatewayCredential,
                            runtimeId = runtimeId,
                            envVars = sessionCatalog.envPayload(method, envValues),
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
            // restart its transport against the new session through the catalog,
            // which retains that transport.
            val reconnected =
                sessionCatalog.reconnectListingToGateway(
                    serverId = serverId,
                    gatewaySource = gatewaySource,
                    restart = restart,
                    authMethodId = method.id,
                    displayName = currentServer.name,
                )
            if (!reconnected) {
                _pendingAuthentication.update {
                    it?.copy(
                        authErrorMessage = "Failed to reconnect to ${currentServer.name}",
                        // The gateway closed the old runtime once the new one started.
                        gatewayRuntimeId = restart.runtimeId,
                    )
                }
                _isLoading.value = false
                return null
            }
            return restart.runtimeId
        }

        /**
         * Handles gateway-backed env authentication by restarting the runtime with env vars.
         *
         * Each failure path exits early — no auth context, an unreadable stored copy, a failed
         * restart, a failed reconnect — so the exit count is inherent to the sequence rather
         * than a sign of nested conditions that want flattening.
         */
        @Suppress("ReturnCount")
        private suspend fun authenticateGatewayEnvVar(
            pending: SessionListPendingAuthentication,
            method: CatalogAuthMethod,
            envValues: Map<String, String>,
        ) {
            // Gateway-backed env auth requires a fresh process environment. Restart
            // the gateway runtime with the saved values, reconnect, authenticate,
            // then retry the original session action.
            val context = resolveAuthContext() ?: return
            val currentServer = context.server
            val gatewaySource = context.gatewaySource

            try {
                sessionCatalog.persistEnvValues(currentServer.id, method, envValues)
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
            val runtimeId =
                restartAndReconnect(
                    pending = pending,
                    currentServer = currentServer,
                    gatewaySource = gatewaySource,
                    method = method,
                    envValues = envValues,
                ) ?: return

            // Keep the whole env-auth sequence on the transport that owns the
            // socket — the same transport the catalog just reconnected.
            if (!sessionCatalog.initializeListing(serverId)) {
                _pendingAuthentication.update {
                    it?.copy(
                        authErrorMessage = "Failed to initialize ${currentServer.name}",
                        gatewayRuntimeId = runtimeId,
                    )
                }
                _isLoading.value = false
                return
            }

            when (val result = sessionCatalog.authenticateListing(serverId, method.id)) {
                is ListingAuthentication.Failed -> {
                    _pendingAuthentication.update {
                        it?.copy(authErrorMessage = result.message, gatewayRuntimeId = runtimeId)
                    }
                    _isLoading.value = false
                    return
                }

                ListingAuthentication.Succeeded -> Unit
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

    /**
     * Removing a worktree needs the user's confirm: it deletes the worktree's [chatCount] chats,
     * and with [discardChanges] its uncommitted changes too. Confirming hands this back to
     * [SessionListViewModel.removeWorktree].
     */
    data class ConfirmRemoveWorktree(
        val sessionId: String,
        val worktreeId: String,
        val branch: String,
        val chatCount: Int,
        val discardChanges: Boolean,
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
