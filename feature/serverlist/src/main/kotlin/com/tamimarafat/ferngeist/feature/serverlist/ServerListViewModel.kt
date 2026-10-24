package com.tamimarafat.ferngeist.feature.serverlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentAuthMethod
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentAuthOutcome
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLaunchOutcome
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLaunchSession
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLauncher
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val connectionState: ChatConnectionState = ChatConnectionState.Disconnected,
    val connectingServerId: String? = null,
    val connectedServerState: ConnectedServerState? = null,
    val showConnectionError: String? = null,
    val pendingAuthentication: PendingAuthentication? = null,
    val pendingLaunchConsent: PendingLaunchConsent? = null,
)

data class RecentSession(
    val sessionId: String,
    val title: String?,
    val serverId: String,
    val target: LaunchableTarget,
    val cwd: String?,
    val updatedAt: Long?,
)

data class PendingAuthentication(
    val serverId: String,
    val serverName: String,
    val agentName: String,
    val authMethods: List<AgentAuthMethod>,
    val persistedEnvValues: Map<String, String> = emptyMap(),
    val authErrorMessage: String? = null,
    /** True when a gateway runtime backs this agent, so its env vars arrive by restarting it. */
    val gatewayBacked: Boolean = false,
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
        private val repos: ServerRepositories,
        private val stores: ServerLocalStores,
        private val agentLauncher: AgentLauncher,
        private val projectCatalog: ProjectCatalog,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /** This screen's verification transport; [AgentLauncher] owns everything behind it. */
        private val session: AgentLaunchSession = agentLauncher.session(viewModelScope)

        val servers: StateFlow<List<LaunchableTarget>> =
            repos.launchableTargetRepository
                .getTargets()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val hasGateways: StateFlow<Boolean> =
            repos.gatewaySourceRepository
                .getGateways()
                .map { gateways -> gateways.isNotEmpty() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        val projects: StateFlow<List<ProjectSummary>> =
            projectCatalog.state
                .map { it.projects }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), projectCatalog.state.value.projects)
        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        /** Server ids with a live hub-tracked chat. Dots track this, not the verification socket. */
        val liveServerIds: StateFlow<Set<String>> =
            agentLauncher.liveServerIds
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

        val recentSessions: StateFlow<List<RecentSession>> =
            repos.sessionRepository
                .getRecentSessions(RECENT_SESSIONS_LIMIT)
                .map { summaries ->
                    summaries
                        .filter { summary ->
                            summary.serverId.isNotBlank().also { valid ->
                                if (!valid) Log.w(LOG_TAG, "Skipping session ${summary.id}: blank serverId")
                            }
                        }.mapNotNull { summary ->
                            val target =
                                repos.launchableTargetRepository.getTarget(summary.serverId) ?: return@mapNotNull null
                            RecentSession(
                                sessionId = summary.id,
                                title = summary.title,
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
            // Observe the verification transport's state changes
            viewModelScope.launch {
                session.connectionState.collect { state ->
                    _uiState.update { it.copy(connectionState = state) }
                }
            }

            // Observe the agent events a launch can also raise on its own
            viewModelScope.launch {
                session.events.collect { event ->
                    _uiState.update { it.withLaunchEvent(event) }
                }
            }

            // Mark loaded only once BOTH underlying queries have actually run. `servers`
            // and `recentSessions` are each StateFlows seeded with emptyList(), so
            // collecting either clears the flag on that synthetic initial value — a
            // frame before any real row. That is what flashed "no agents yet" before the
            // list, and let the launch splash drop while the recents were still empty so
            // the sheet popped open a frame later. Each repository flow's first emission
            // is a completed query, empty when there is genuinely nothing, so awaiting
            // one from each is the honest signal. `first()` cancels on delivery: two
            // short-lived subscriptions, no lasting duplicate collectors alongside the
            // stateIn ones.
            viewModelScope.launch {
                repos.launchableTargetRepository.getTargets().first()
                repos.sessionRepository.getRecentSessions(RECENT_SESSIONS_LIMIT).first()
                projectCatalog.state.first { it.isReady }
                _isLoading.value = false
            }
        }

        /** The home appearing is a fetch trigger; the catalog throttles repeats. */
        fun refreshProjects() {
            projectCatalog.refresh()
        }

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
                withContext(ioDispatcher) {
                    stores.agentLaunchConsentStore.hasConsent(consentKey)
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
                    session.isConnected &&
                    _uiState.value.pendingAuthentication == null
                ) {
                    _uiState.update { it.copy(connectingServerId = null) }
                    openConnectedServer(server.id, server.name)
                    return@launch
                }
                // A hub-tracked chat owns this agent's gateway slot: its transport
                // is already connected+initialized, so skip the tap handshake —
                // a fresh start+connect would steal the single-attach slot and
                // evict the live chat socket (gateway logs the intentional
                // CloseNow as "use of closed network connection").
                if (agentLauncher.hasWarmChat(server.id) && _uiState.value.pendingAuthentication == null) {
                    _uiState.update { it.copy(connectingServerId = null) }
                    openConnectedServer(server.id, server.name)
                    return@launch
                }

                if (!hasLaunchConsent(server)) return@launch
                // Gateway-backed agents should start from a fresh ACP transport. If we
                // request a new gateway handoff before closing the existing socket,
                // the old runtime can survive long enough to be reused, which some
                // stdio agents do not handle correctly on reattach.
                if (_uiState.value.connectionState !is ChatConnectionState.Disconnected) {
                    withContext(ioDispatcher) {
                        session.disconnect()
                    }
                }

                // Update UI state
                _uiState.update {
                    it.copy(
                        connectingServerId = server.id,
                        connectionState = ChatConnectionState.Connecting,
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

                when (val outcome = session.launch(server)) {
                    is AgentLaunchOutcome.Ready -> openLaunchedServer(server, outcome)
                    is AgentLaunchOutcome.AuthenticationRequired ->
                        _uiState.update { it.withAuthenticationPrompt(server, outcome) }

                    is AgentLaunchOutcome.Failed -> _uiState.update { it.withLaunchFailure(outcome) }
                }
            }
        }

        /**
         * Applies a successful launch: remembers an unprompted auth method, then
         * navigates. The launch already hung the verification transport up, so the
         * single-attach slot is free for the session list.
         */
        private suspend fun openLaunchedServer(
            server: LaunchableTarget,
            outcome: AgentLaunchOutcome.Ready,
        ) {
            outcome.authenticatedMethodId?.let { authenticatedMethodId ->
                savePreferredAuthMethod(server.id, authenticatedMethodId)
            }
            // Keep connectingServerId set through the prewarm listing:
            // the spinner stays up until navigation, with no dead gap.
            _uiState.update {
                it.copy(
                    pendingAuthentication = null,
                    connectedServerState =
                        it.connectedServerState?.copy(
                            agentName = outcome.agentName,
                            capabilities = outcome.capabilities,
                            isInitializing = false,
                        ),
                )
            }
            openConnectedServer(server.id, server.name, prewarmSessions = true)
        }

        fun authenticate(
            serverId: String,
            methodId: String,
            envValues: Map<String, String> = emptyMap(),
        ) {
            viewModelScope.launch {
                val pending = _uiState.value.pendingAuthentication
                if (pending == null || pending.serverId != serverId) return@launch
                if (pending.authMethods.none { it.id == methodId }) return@launch

                _uiState.update {
                    it.copy(
                        connectingServerId = serverId,
                        showConnectionError = null,
                        pendingAuthentication = pending.copy(authErrorMessage = null),
                    )
                }

                val outcome = session.authenticate(serverId, methodId, envValues)
                if (outcome is AgentAuthOutcome.Failure) {
                    _uiState.update {
                        it.copy(
                            connectingServerId = null,
                            showConnectionError = outcome.connectionError ?: it.showConnectionError,
                            pendingAuthentication =
                                it.pendingAuthentication?.copy(authErrorMessage = outcome.message),
                        )
                    }
                    return@launch
                }

                savePreferredAuthMethod(serverId, methodId)

                // Spinner stays up through the prewarm listing below;
                // openConnectedServer clears it on navigate.
                _uiState.update {
                    it.copy(
                        pendingAuthentication = null,
                        connectedServerState = it.connectedServerState?.copy(isInitializing = false),
                    )
                }
                openConnectedServer(serverId, pending.serverName, prewarmSessions = true)
            }
        }

        fun retryPendingAuthentication(serverId: String) {
            viewModelScope.launch {
                val server =
                    withContext(ioDispatcher) {
                        repos.launchableTargetRepository.getTarget(serverId)
                    } ?: return@launch

                withContext(ioDispatcher) {
                    session.disconnect()
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
                    withContext(ioDispatcher) {
                        repos.launchableTargetRepository.getTarget(serverId)
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
                withContext(ioDispatcher) {
                    stores.agentLaunchConsentStore.setConsent(consentKey, true)
                }
                _uiState.update { it.copy(pendingLaunchConsent = null) }
                connectAndOpenServer(gatewayTarget)
            }
        }

        fun disconnect() {
            viewModelScope.launch {
                session.disconnect()
                _uiState.update {
                    it.copy(
                        connectionState = ChatConnectionState.Disconnected,
                        connectingServerId = null,
                        connectedServerState = null,
                    )
                }
            }
        }

        fun deleteServer(serverId: String) {
            viewModelScope.launch {
                withContext(ioDispatcher) {
                    val target = repos.launchableTargetRepository.getTarget(serverId)
                    if (target is LaunchableTarget.GatewayAgent) {
                        val consentKey =
                            buildLaunchConsentKey(
                                gatewaySourceId = target.gatewaySource.id,
                                agentId = target.binding.agentId,
                            )
                        stores.agentLaunchConsentStore.clearByPrefix(consentKey)
                    }
                    stores.authEnvValueStore.deleteValues(serverId)
                    stores.recentCwdStore.clear(serverId)
                    // Prefix-based cleanup: clearByPrefix removes all entries whose DataStore key
                    // starts with the given prefix. Trailing ":" prevents cross-server ID matches.
                    stores.recentSelectionStore.clearByPrefix("config_option:$serverId:")
                    stores.recentSelectionStore.clearByPrefix("commands:$serverId:")
                    repos.sessionRepository.clearSessions(serverId)
                    repos.sessionSettingsRepository.deleteSettings(serverId)
                    repos.launchableTargetRepository.deleteTarget(serverId)
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

        private suspend fun savePreferredAuthMethod(
            targetId: String,
            methodId: String,
        ) {
            withContext(ioDispatcher) {
                repos.launchableTargetRepository.updatePreferredAuthMethod(targetId, methodId)
            }
        }

        /**
         * Warms the session cache through the launcher's listing seam before
         * navigating, so the session list paints rows instantly instead of
         * showing a second spinner. Best-effort: any failure (including auth
         * challenges) still navigates — the session screen surfaces listing
         * failures exactly as it does today.
         *
         * Single choke point for navigation: the spinner clears exactly
         * when the next screen is coming, so no dead gap — and no
         * stranded spinner — can survive past this point.
         */
        private suspend fun openConnectedServer(
            serverId: String,
            serverName: String,
            prewarmSessions: Boolean = false,
        ) {
            if (prewarmSessions) {
                agentLauncher.prewarmSessions(serverId)
            }
            _uiState.update { it.copy(connectingServerId = null) }
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
