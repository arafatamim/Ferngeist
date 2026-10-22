package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.agentclientprotocol.annotations.UnstableApi
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.ScopeOption
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.worktreeScopeOptions
import com.tamimarafat.ferngeist.gateway.GatewayWorktree

internal class SessionListState(
    val sessions: List<SessionSummary>,
    val liveSessionIds: Set<String>,
    /** False only until the cache's first emission; see `SessionRows.loaded`. */
    val cacheRead: Boolean,
    val currentCwd: String?,
    val connectionState: ChatConnectionState,
    val connectionDiagnostics: ChatConnectionDiagnostics,
    val pendingAuthentication: SessionListPendingAuthentication?,
    val snackbarHostState: SnackbarHostState,
    val recentCwds: List<String>,
    /** Every project the project sheet offers. */
    val projects: List<String>,
    val showCwdDialog: MutableState<Boolean>,
    val cwdDialogValue: MutableState<String>,
    val showConnectionStatusDialog: MutableState<Boolean>,
    val selectedAuthMethodId: MutableState<String?>,
    val envValues: MutableMap<String, String>,
    val isRefreshing: Boolean,
    val pullToRefreshState: PullToRefreshState,
    val supportsSessionList: Boolean,
    /** True when the agent advertised `sessionCapabilities.delete` — gates the menu's Delete item. */
    val supportsSessionDelete: Boolean,
    val serverName: String,
    val scrollBehavior: TopAppBarScrollBehavior,
    val hasConsumedLaunchCreate: MutableState<Boolean>,
    /** Managed worktrees, or null when this gateway has none to show (older gateway). */
    val worktrees: List<GatewayWorktree>?,
    val removingWorktreeIds: Set<String>,
    /** `cwdKey`s of chat folders gone from disk; their chats sit in a collapsed section. */
    val missingCwds: Set<String>,
    val showWorktreeSheet: MutableState<Boolean>,
    /** Which part of the filtered repo the list shows; New chat starts there. */
    val scope: WorktreeScope,
    /** The filtered repo's worktrees the scope menu offers, managed and not. */
    val scopeOptions: List<ScopeOption>,
    /** True when the filter names a repo on a gateway with worktrees: New chat becomes a toolbar. */
    val scopeEnabled: Boolean,
    val toolbarExpanded: MutableState<Boolean>,
    val creatingWorktree: Boolean,
    val worktreeError: String?,
    /** Set when the gateway refused a removal over uncommitted changes. */
    val pendingWorktreeRemoval: MutableState<SessionListEvent.ConfirmRemoveWorktree?>,
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
    UnstableApi::class,
)
@Composable
internal fun rememberSessionListState(
    viewModel: SessionListViewModel,
    navArgName: String?,
    loadedName: String?,
): SessionListState {
    val sessionSettings by viewModel.sessionSettings.collectAsState()
    val agentCapabilities by viewModel.agentCapabilities.collectAsState()
    val pendingAuthentication by viewModel.pendingAuthentication.collectAsState()
    val sessionRows by viewModel.sessionRows.collectAsState()
    val currentCwd = sessionSettings.cwd
    val serverName =
        resolveServerDisplayName(
            navArgName,
            loadedName,
            stringResource(R.string.sessionlist_topbar_title),
        )
    val supportsSessionList = agentCapabilities?.sessionCapabilities?.list != null
    val supportsSessionDelete = agentCapabilities?.sessionCapabilities?.delete != null
    val worktrees = viewModel.worktrees.collectAsState().value
    val scope = viewModel.scope.collectAsState().value
    val scopeOptions =
        remember(currentCwd, worktrees, sessionRows.inRepo) {
            currentCwd
                ?.takeIf { it.isNotBlank() }
                ?.let { worktreeScopeOptions(it, worktrees.orEmpty(), sessionRows.inRepo) }
                .orEmpty()
        }
    return SessionListState(
        sessions = sessionRows.rows,
        liveSessionIds = viewModel.liveSessionIds.collectAsState().value,
        cacheRead = sessionRows.loaded,
        currentCwd = currentCwd,
        connectionState = viewModel.connectionState.collectAsState().value,
        connectionDiagnostics = viewModel.connectionDiagnostics.collectAsState().value,
        pendingAuthentication = pendingAuthentication,
        snackbarHostState = remember { SnackbarHostState() },
        recentCwds = viewModel.recentCwds.collectAsState().value,
        projects = viewModel.projects.collectAsState().value,
        showCwdDialog = remember { mutableStateOf(false) },
        cwdDialogValue = remember(currentCwd) { mutableStateOf(currentCwd.orEmpty()) },
        showConnectionStatusDialog = remember { mutableStateOf(false) },
        selectedAuthMethodId =
            rememberSaveable(
                pendingAuthentication?.serverId,
                pendingAuthentication?.pendingAction,
            ) {
                mutableStateOf(
                    pendingAuthentication?.preferredAuthMethodId
                        ?: pendingAuthentication?.authMethods?.firstOrNull()?.id,
                )
            },
        envValues =
            remember(
                pendingAuthentication?.serverId,
                pendingAuthentication?.pendingAction,
            ) { mutableStateMapOf<String, String>() },
        isRefreshing = viewModel.refreshing.collectAsState().value,
        pullToRefreshState = rememberPullToRefreshState(),
        supportsSessionList = supportsSessionList,
        supportsSessionDelete = supportsSessionDelete,
        serverName = serverName,
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
        hasConsumedLaunchCreate = rememberSaveable { mutableStateOf(false) },
        worktrees = worktrees,
        removingWorktreeIds = viewModel.removingWorktreeIds.collectAsState().value,
        missingCwds = viewModel.missingCwds.collectAsState().value,
        showWorktreeSheet = rememberSaveable { mutableStateOf(false) },
        scope = scope,
        scopeOptions = scopeOptions,
        scopeEnabled = worktrees != null && !currentCwd.isNullOrBlank(),
        toolbarExpanded = rememberSaveable { mutableStateOf(true) },
        creatingWorktree = viewModel.creatingWorktree.collectAsState().value,
        worktreeError = viewModel.worktreeError.collectAsState().value,
        pendingWorktreeRemoval = remember { mutableStateOf(null) },
    )
}

@Composable
internal fun SessionListEventEffects(
    viewModel: SessionListViewModel,
    state: SessionListState,
    pendingAuthentication: SessionListPendingAuthentication?,
    snackbarHostState: SnackbarHostState,
    envValues: MutableMap<String, String>,
    selectedAuthMethodId: MutableState<String?>,
    hasConsumedLaunchCreate: MutableState<Boolean>,
    openCreateSessionDialogOnLaunch: Boolean,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
) {
    // Re-populate envValues from persisted values whenever the pending auth changes.
    // This ensures the dialog reflects the most recent server-saved env vars.
    LaunchedEffect(
        pendingAuthentication?.serverId,
        pendingAuthentication?.pendingAction,
        pendingAuthentication?.persistedEnvValues,
    ) {
        envValues.clear()
        pendingAuthentication?.persistedEnvValues?.forEach { (name, value) ->
            envValues[name] = value
        }
        selectedAuthMethodId.value = pendingAuthentication?.preferredAuthMethodId
            ?: pendingAuthentication?.authMethods?.firstOrNull()?.id
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionListEvent.NavigateToChat -> {
                    // A worktree create leaves its sheet open until its chat is ready.
                    state.showCwdDialog.value = false
                    state.showWorktreeSheet.value = false
                    onNavigateToChat(
                        event.sessionId,
                        event.cwd,
                        event.updatedAt,
                        event.title,
                    )
                }

                is SessionListEvent.ShowError -> snackbarHostState.showSnackbar(event.message)

                is SessionListEvent.ConfirmRemoveWorktree -> state.pendingWorktreeRemoval.value = event
            }
        }
    }

    LaunchedEffect(openCreateSessionDialogOnLaunch) {
        if (openCreateSessionDialogOnLaunch && !hasConsumedLaunchCreate.value) {
            hasConsumedLaunchCreate.value = true
            if (state.currentCwd.isNullOrBlank()) {
                state.cwdDialogValue.value = state.currentCwd.orEmpty()
                viewModel.setPendingCreateAfterCwd()
                state.showCwdDialog.value = true
            } else {
                viewModel.createSessionWithCurrentCwd()
            }
        }
    }
}
