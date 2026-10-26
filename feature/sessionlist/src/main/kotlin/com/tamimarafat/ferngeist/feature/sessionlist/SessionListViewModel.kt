package com.tamimarafat.ferngeist.feature.sessionlist

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.catalog.CatalogAuthMethod
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog.ListSessionsResult
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectCatalog
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.LaunchableTargetSessionSettings
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.feature.sessionlist.auth.AuthCompletion
import com.tamimarafat.ferngeist.feature.sessionlist.auth.SessionListAuth
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.ScopeSelection
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.chatsInWorktree
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.displayName
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.effectiveScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.filterSessionsByScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.knownProjects
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.scopeCwd
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.sessionsInRepo
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.worktreeOf
import com.tamimarafat.ferngeist.feature.sessionlist.worktree.SessionWorktrees
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * The auth recovery flows live in [SessionListAuth] and the gateway worktree
 * calls in [SessionWorktrees]. The public intents stay here because the screen
 * binds them by reference; that is why the class carries more functions than
 * detekt's TooManyFunctions budget.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class SessionListViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        savedStateHandle: SavedStateHandle,
        private val sessionCatalog: SessionCatalog,
        private val projectCatalog: ProjectCatalog,
        private val repos: SessionListRepositories,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val serverId: String = savedStateHandle.get<String>("serverId").orEmpty()

        val server: StateFlow<LaunchableTarget?> =
            repos.launchableTargetRepository
                .getTargets()
                .map { servers -> servers.find { it.id == serverId } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val sessionSettings: StateFlow<LaunchableTargetSessionSettings> =
            repos.sessionSettingsRepository
                .getSettings(serverId)
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    LaunchableTargetSessionSettings(targetId = serverId),
                )

        /** Per-target recent working directories, ordered MRU-first. */
        val recentCwds: StateFlow<List<String>> =
            repos.recentCwdStore
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
            repos.sessionRepository
                .getSessions(serverId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val scopeSelection = MutableStateFlow<ScopeSelection?>(null)

        /** The part of the filtered repo the list shows; [WorktreeScope.All] once the repo filter moves. */
        val scope: StateFlow<WorktreeScope> =
            combine(scopeSelection, sessionSettings) { selection, settings -> effectiveScope(selection, settings.cwd) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, WorktreeScope.All)

        /** Sessions currently holding a live gateway connection (for the row dot). */
        val liveSessionIds: StateFlow<Set<String>> =
            sessionCatalog
                .connectedSessionIds(serverId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

        val workingSessionIds: StateFlow<Set<String>> =
            projectCatalog.inProgressChatKeys
                .map { keys ->
                    keys.filter { it.startsWith("$serverId/") }.map { it.removePrefix("$serverId/") }.toSet()
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

        val activityUpdatedAt = projectCatalog.activityUpdatedAt

        /** Sessions a gateway process still runs for this agent, from the last refresh. */
        private val runningSessionIds = MutableStateFlow<Set<String>>(emptySet())

        /** Sessions Close can act on: live here, or still running on the gateway. */
        val closableSessionIds: StateFlow<Set<String>> =
            combine(liveSessionIds, runningSessionIds) { live, running -> live + running }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

        private val outputs = SessionListOutputs()
        val isLoading: StateFlow<Boolean> = outputs.loading.asStateFlow()

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

        val events = outputs.events.asSharedFlow()

        private val auth =
            SessionListAuth(
                serverId = serverId,
                server = server,
                outputs = outputs,
                sessionCatalog = sessionCatalog,
                repos = repos,
                ioDispatcher = ioDispatcher,
            )

        val pendingAuthentication: StateFlow<SessionListPendingAuthentication?> = auth.pendingAuthentication

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
                repos = repos,
                ioDispatcher = ioDispatcher,
                emitEvent = { outputs.events.emit(it) },
                openChat = { repo, path ->
                    selectScope(WorktreeScope.Worktree(path), repo)
                    createSession(path)
                },
            )

        /** Managed worktrees, or null when this gateway has none to show (older gateway). */
        val worktrees: StateFlow<List<GatewayWorktree>?> = sessionWorktrees.managed

        /** True while a worktree create is in flight; the dialog's confirm button stays busy. */
        val creatingWorktree: StateFlow<Boolean> = sessionWorktrees.creating

        /** Inline error for the worktree form (bad repo/branch, existing branch), else null. */
        val worktreeError: StateFlow<String?> = sessionWorktrees.error

        /** `cwdKey`s of chat directories that are gone from disk; their chats can no longer load. */
        val missingCwds: StateFlow<Set<String>> = sessionWorktrees.missingCwds

        /** Every project this agent's chats ran in, for the directory menu; worktrees fold into their repo. */
        val projects: StateFlow<List<String>> =
            combine(
                sessions,
                recentCwds,
                sessionWorktrees.managed,
                sessionWorktrees.missingCwds,
            ) { cached, recent, managed, missing ->
                knownProjects(cached, recent, managed.orEmpty(), missing)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                repos.sessionRepository.getSessions(serverId).map { cached -> cached to true },
                sessionSettings,
                scopeSelection,
                sessionWorktrees.managed,
            ) { (cached, loaded), settings, selection, managed ->
                // Filtering locally keeps the list truthful while the agent is
                // unreachable — the hub's listing can only filter by re-asking
                // the gateway, and its result replaces this same cache.
                val worktreePaths =
                    managed.orEmpty().filter { isSameCwd(it.repo, settings.cwd.orEmpty()) }.map { it.path }
                val inRepo = sessionsInRepo(cached, settings.cwd, worktreePaths)
                val scope = effectiveScope(selection, settings.cwd)
                SessionRows(rows = filterSessionsByScope(inRepo, settings.cwd, scope), inRepo = inRepo, loaded = loaded)
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
                SessionRows(rows = emptyList(), inRepo = emptyList(), loaded = false),
            )

        private val _removingWorktreeIds = MutableStateFlow<Set<String>>(emptySet())

        /** Ids of worktrees whose removal is in flight; their chat cards show as busy. */
        val removingWorktreeIds: StateFlow<Set<String>> = _removingWorktreeIds.asStateFlow()
        private var refreshJob: kotlinx.coroutines.Job? = null
        private var refreshGeneration = 0
        private var worktreeRefreshJob: kotlinx.coroutines.Job? = null

        /** True when the last listing attempt never reached the agent. */
        private var lastListingFailed = false

        init {
            onAppear()
            // The server list's launch has usually just listed over its own connection.
            refreshSessions(reuseRecent = true)
            viewModelScope.launch {
                // The gateway may not know the newly filtered repo yet; asking names it.
                sessionSettings.map { it.cwd }.distinctUntilChanged().collectLatest { cwd ->
                    sessionWorktrees.refresh(cwd, chatDirs())
                }
            }
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
                    if (hasWarmHub && outputs.loading.value && sessions.value.isNotEmpty()) {
                        android.util.Log.d(
                            "TSList",
                            "warm hub appeared — cancelling stale isLoading sessions=${sessions.value.size}",
                        )
                        refreshJob?.cancel()
                        outputs.loading.value = false
                        _refreshing.value = false
                    }
                }
            }
        }

        fun onAppear() {
            projectCatalog.refresh()
        }

        /**
         * Asks the gateway which sessions it still runs, so the list offers Close only where it
         * can act. Only when the transport worked — a failed listing means the gateway is likely
         * unreachable too, so a second call could only fail. A failed ask keeps the last answer.
         */
        private suspend fun refreshRunningSessions(result: ListSessionsResult) {
            if (result !is ListSessionsResult.Listed && result !is ListSessionsResult.Unsupported) return
            sessionCatalog.runningSessionIds(serverId)?.let { runningSessionIds.value = it }
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
            if (isUserInitiated) projectCatalog.refresh()
            if (refreshJob?.isActive == true && !isUserInitiated) return // coalesce overlapping cold listings
            refreshJob?.cancel()
            val generation = ++refreshGeneration
            refreshJob =
                viewModelScope.launch {
                    try {
                        if (isUserInitiated) _refreshing.value = true
                        outputs.loading.value = true
                        // Always the whole list: the cache keeps only what a listing returns,
                        // and an agent filtering by cwd matches it exactly, so a filtered
                        // listing would drop the repo's worktree chats (and every other
                        // directory). The cwd filter is applied locally by sessionRows.
                        val result = sessionCatalog.listSessions(serverId, cwd = null, reuseRecent = reuseRecent)
                        when (result) {
                            is ListSessionsResult.Listed -> acceptListing(result.agentCapabilities)
                            is ListSessionsResult.Unsupported -> acceptListing(result.agentCapabilities)
                            is ListSessionsResult.AuthRequired -> {
                                lastListingFailed = false
                                auth.handleAuthenticationRequired(result, PendingAuthAction.RefreshSessions)
                                return@launch
                            }

                            is ListSessionsResult.Failed -> reportListingFailure(result, isUserInitiated)
                        }
                        // Paint first, then reconcile leases in the background
                        // (details in publishListingAndRefresh).
                        publishListingAndRefresh(result, generation)
                    } finally {
                        if (generation == refreshGeneration) {
                            outputs.loading.value = false
                            _refreshing.value = false
                        }
                    }
                }
        }

        private fun acceptListing(agentCapabilities: AgentCapabilities?) {
            lastListingFailed = false
            agentCapabilities?.let { _agentCapabilities.value = it }
        }

        private suspend fun reportListingFailure(
            failed: ListSessionsResult.Failed,
            isUserInitiated: Boolean,
        ) {
            val alreadyFailing = lastListingFailed
            lastListingFailed = true
            // Silent on an auto refresh with cached rows; a warm socket
            // mid-reconnect would otherwise toast on every return from
            // chat. Still loud for a pull-to-refresh, or once when the
            // user is looking at nothing (not on every poll after it).
            if (isUserInitiated || (sessions.value.isEmpty() && !alreadyFailing)) {
                outputs.events.emit(SessionListEvent.ShowError(failed.message))
            }
        }

        /**
         * Publishes catalog snapshots and clears the loading flags for first
         * paint, then asks the gateway for its running sessions in the
         * background: that up-to-10s REST window must never extend the spinner.
         */
        private fun publishListingAndRefresh(
            result: ListSessionsResult,
            generation: Int,
        ) {
            syncCatalogObservables()
            if (generation == refreshGeneration) {
                outputs.loading.value = false
                _refreshing.value = false
            }
            viewModelScope.launch {
                refreshRunningSessions(result)
                sessionWorktrees.refresh(sessionSettings.value.cwd, chatDirs())
                refreshMissingCwds()
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
            worktreeRefreshJob =
                viewModelScope.launch {
                    sessionWorktrees.refresh(sessionSettings.value.cwd, chatDirs())
                    refreshMissingCwds()
                }
        }

        /** Every folder a chat ran in or was recently picked, so the directory menu folds each worktree. */
        private fun chatDirs(): List<String> = sessions.value.mapNotNull { it.cwd } + recentCwds.value

        /** Asks the gateway which of [chatDirs] are gone, so the projects menu offers only folders that exist. */
        private suspend fun refreshMissingCwds() {
            sessionWorktrees.refreshMissing(chatDirs())
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
            outputs.loading.value = false
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
                outputs.events.emit(
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
            viewModelScope.launch(ioDispatcher) {
                if (refuseWhileStreaming(sessionId)) return@launch
                val target = repos.launchableTargetRepository.getTarget(serverId)
                if (target !is LaunchableTarget.GatewayAgent) {
                    outputs.events.emit(SessionListEvent.ShowError("Close is only available for gateway sessions."))
                    return@launch
                }
                runCatching {
                    sessionCatalog.closeSession(serverId, sessionId)
                }.onSuccess { closed ->
                    if (!closed) {
                        outputs.events.emit(SessionListEvent.ShowError("This session has no live process to close."))
                    }
                }.onFailure { error ->
                    outputs.events.emit(
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
            viewModelScope.launch(ioDispatcher) {
                if (refuseWhileStreaming(sessionId)) return@launch
                runCatching {
                    sessionCatalog.deleteSession(serverId, sessionId)
                }.onSuccess { deleted ->
                    if (!deleted) {
                        outputs.events.emit(SessionListEvent.ShowError("This session could not be found."))
                    }
                }.onFailure { error ->
                    outputs.events.emit(
                        SessionListEvent.ShowError(error.message ?: "Failed to delete session."),
                    )
                }
            }
        }

        /** Tells the user a responding chat cannot be closed or deleted from here; true when it refused. */
        private suspend fun refuseWhileStreaming(sessionId: String): Boolean {
            if (!sessionCatalog.isStreaming(serverId, sessionId)) return false
            outputs.events.emit(
                SessionListEvent.ShowError(
                    "This session is still responding. Cancel or close it from inside the chat first.",
                ),
            )
            return true
        }

        /**
         * Persists [cwd] to settings, records it in the recent list, then
         * refreshes sessions. The refresh counts as user-initiated: the list
         * already narrows to [cwd] locally, so a listing the agent could not
         * answer must say so instead of passing for an empty directory.
         */
        fun updateCurrentCwd(picked: String) {
            // A worktree is not a project of its own: pick its repo, scoped to that worktree, so a
            // new worktree made from it lands beside it rather than inside it.
            val worktree =
                worktreeOf(picked.trim(), sessionWorktrees.managed.value.orEmpty())
            val cwd = worktree?.repo ?: picked
            worktree?.let { selectScope(WorktreeScope.Worktree(it.path), it.repo) }
            viewModelScope.launch {
                repos.sessionSettingsRepository.updateCwd(serverId, cwd)
                val normalized = cwd.trim().ifBlank { "" }
                if (normalized.isNotBlank()) {
                    repos.recentCwdStore.addCwd(serverId, normalized)
                }
                refreshSessions(isUserInitiated = true)
                if (pendingCreateAfterCwd) {
                    pendingCreateAfterCwd = false
                    // In scope, so a picked worktree gets the chat rather than its repo.
                    createSessionInScope()
                }
            }
        }

        fun setPendingCreateAfterCwd() {
            pendingCreateAfterCwd = true
        }

        /** Removes [cwd] from the recent list without affecting the current filter. */
        fun removeRecentCwd(cwd: String) {
            viewModelScope.launch {
                repos.recentCwdStore.removeCwd(serverId, cwd)
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

        /** [repo]'s refs for the new-worktree form; empty when unknown. */
        suspend fun repoRefs(repo: String): GatewayGitRefs = sessionWorktrees.repoRefs(repo)

        /** Picks which part of the filtered repo the list shows and where New chat starts. */
        fun selectScope(
            scope: WorktreeScope,
            repo: String? = sessionSettings.value.cwd,
        ) {
            scopeSelection.value = repo?.takeIf { it.isNotBlank() }?.let { ScopeSelection(it, scope) }
        }

        /** New chat for the picked scope: the repo itself, or the worktree. */
        fun createSessionInScope() {
            val repo =
                sessionSettings.value.cwd
                    ?.trim()
                    ?.ifBlank { null } ?: return
            createSession(scopeCwd(repo, scope.value))
        }

        /**
         * Asks the user to confirm removing a worktree — the one [sessionId]'s chat runs in, or
         * one picked from the scope menu with no chat — naming the chats that go with it. Removal deletes every one of them, so it never runs unconfirmed.
         * A worktree the listing already shows as dirty goes straight to the discard confirm.
         */
        fun requestRemoveWorktree(
            sessionId: String?,
            worktreeId: String,
        ) {
            val worktree = worktrees.value?.firstOrNull { it.id == worktreeId } ?: return
            viewModelScope.launch {
                outputs.events.emit(
                    SessionListEvent.ConfirmRemoveWorktree(
                        sessionId = sessionId,
                        worktreeId = worktreeId,
                        branch = worktree.displayName,
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
            viewModelScope.launch(ioDispatcher) {
                _removingWorktreeIds.update { it + request.worktreeId }
                try {
                    removeConfirmedWorktree(request)
                } finally {
                    _removingWorktreeIds.update { it - request.worktreeId }
                }
            }
        }

        private suspend fun removeConfirmedWorktree(request: SessionListEvent.ConfirmRemoveWorktree) {
            val path = worktrees.value?.firstOrNull { it.id == request.worktreeId }?.path
            val chats = chatsInWorktree(sessions.value, request.sessionId, path)
            if (chats.any { sessionCatalog.isStreaming(serverId, it) }) {
                outputs.events.emit(
                    SessionListEvent.ShowError(
                        context.getString(R.string.sessionlist_worktree_error_chat_busy),
                    ),
                )
                return
            }
            if (repos.launchableTargetRepository.getTarget(serverId) !is LaunchableTarget.GatewayAgent) {
                outputs.events.emit(
                    SessionListEvent.ShowError(
                        context.getString(R.string.sessionlist_worktree_error_remove_gateway_only),
                    ),
                )
                return
            }
            for (chat in chats) {
                runCatching { sessionCatalog.closeSession(serverId, chat) }
            }
            if (!sessionWorktrees.remove(request)) return
            // The picked worktree is gone; leave the scope on something that exists.
            val picked = (scope.value as? WorktreeScope.Worktree)?.path
            if (picked != null && path != null && isSameCwd(picked, path)) selectScope(WorktreeScope.All)
            for (chat in chats) {
                runCatching { sessionCatalog.deleteSession(serverId, chat) }
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
                auth.authenticate(methodId, envValues)?.let(::completeAuthentication)
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
                auth.reconnect()?.let(::retryPendingAction)
            }
        }

        /** Dismisses the authentication dialog and clears loading state. */
        fun dismissAuthenticationPrompt() {
            auth.dismiss()
        }

        /** Remembers the method that worked for this server, then retries the blocked action. */
        private fun completeAuthentication(completion: AuthCompletion) {
            viewModelScope.launch(ioDispatcher) {
                repos.launchableTargetRepository.updatePreferredAuthMethod(completion.targetId, completion.methodId)
            }
            retryPendingAction(completion.retry)
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
        val sessionId: String?,
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
    /** The rows before the worktree scope narrows them; the scope menu counts chats from these. */
    val inRepo: List<SessionSummary>,
    val loaded: Boolean,
)
