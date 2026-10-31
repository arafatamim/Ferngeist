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
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.ScopeOption
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.worktreeScopeOptions
import com.tamimarafat.ferngeist.gateway.GatewayWorktree

/** Which rows hold a live connection here, and which Close can act on. */
internal class RowPresence(
    val live: Set<String>,
    val turnStatuses: Map<String, TurnActivity>,
    val activityUpdatedAt: Map<String, Long>,
    /** Live here, or still running on the gateway. */
    val closable: Set<String>,
)

/** The session rows the list paints, and what the agent allows for them. */
internal class SessionRowsUi(
    val sessions: List<SessionSummary>,
    val presence: RowPresence,
    /** False only until the cache's first emission; see `SessionRows.loaded`. */
    val cacheRead: Boolean,
    /** `cwdKey`s of chat folders gone from disk; their chats sit in a collapsed section. */
    val missingCwds: Set<String>,
    val supportsSessionList: Boolean,
    /** True when the agent advertised `sessionCapabilities.delete` — gates the menu's Delete item. */
    val supportsSessionDelete: Boolean,
)

/** The connection pill and its details, and the auth challenge the agent raised. */
internal class ConnectionUi(
    val connectionState: ChatConnectionState,
    val connectionDiagnostics: ChatConnectionDiagnostics,
    val showConnectionStatusDialog: MutableState<Boolean>,
    val pendingAuthentication: SessionListPendingAuthentication?,
    val selectedAuthMethodId: MutableState<String?>,
    val envValues: MutableMap<String, String>,
)

/** Screen chrome and refresh: the snackbar, the title and scroll, pull-to-refresh, and the launch latch. */
internal class ScreenUi(
    val snackbarHostState: SnackbarHostState,
    val serverName: String,
    val scrollBehavior: TopAppBarScrollBehavior,
    val isRefreshing: Boolean,
    val pullToRefreshState: PullToRefreshState,
    val hasConsumedLaunchCreate: MutableState<Boolean>,
)

/** The working-folder picker: the current folder, the recent ones and the projects it offers. */
internal class ProjectUi(
    val currentCwd: String?,
    val recentCwds: List<String>,
    /** Every project the project sheet offers. */
    val projects: List<String>,
    val showCwdDialog: MutableState<Boolean>,
    val cwdDialogValue: MutableState<String>,
)

/** The gateway's worktrees, and the state of creating and removing them. */
internal class WorktreeUi(
    /** Managed worktrees, or null when this gateway has none to show (older gateway). */
    val worktrees: List<GatewayWorktree>?,
    val removingWorktreeIds: Set<String>,
    val creatingWorktree: Boolean,
    val worktreeError: String?,
    val showWorktreeSheet: MutableState<Boolean>,
    /** Set when the gateway refused a removal over uncommitted changes. */
    val pendingWorktreeRemoval: MutableState<SessionListEvent.ConfirmRemoveWorktree?>,
)

/** Which part of the filtered repo the list shows, and the toolbar that picks it. */
internal class ScopeUi(
    /** Which part of the filtered repo the list shows; New chat starts there. */
    val scope: WorktreeScope,
    /** The filtered repo's worktrees the scope menu offers, managed and not. */
    val scopeOptions: List<ScopeOption>,
    /** True when the filter names a repo on a gateway with worktrees: New chat becomes a toolbar. */
    val scopeEnabled: Boolean,
    val toolbarExpanded: MutableState<Boolean>,
)

internal class SessionListState(
    val rows: SessionRowsUi,
    val connection: ConnectionUi,
    val screen: ScreenUi,
    val project: ProjectUi,
    val worktree: WorktreeUi,
    val repoScope: ScopeUi,
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
    val liveSessionIds = viewModel.liveSessionIds.collectAsState().value
    val turnStatuses = viewModel.turnStatuses.collectAsState().value
    val activityUpdatedAt = viewModel.activityUpdatedAt.collectAsState().value
    val closableSessionIds = viewModel.closableSessionIds.collectAsState().value
    val connectionState = viewModel.connectionState.collectAsState().value
    val connectionDiagnostics = viewModel.connectionDiagnostics.collectAsState().value
    val snackbarHostState = remember { SnackbarHostState() }
    val recentCwds = viewModel.recentCwds.collectAsState().value
    val projects = viewModel.projects.collectAsState().value
    val showCwdDialog = remember { mutableStateOf(false) }
    val cwdDialogValue = remember(currentCwd) { mutableStateOf(currentCwd.orEmpty()) }
    val showConnectionStatusDialog = remember { mutableStateOf(false) }
    val selectedAuthMethodId =
        rememberSaveable(
            pendingAuthentication?.serverId,
            pendingAuthentication?.pendingAction,
        ) {
            mutableStateOf(
                pendingAuthentication?.preferredAuthMethodId
                    ?: pendingAuthentication?.authMethods?.firstOrNull()?.id,
            )
        }
    val envValues =
        remember(
            pendingAuthentication?.serverId,
            pendingAuthentication?.pendingAction,
        ) { mutableStateMapOf<String, String>() }
    val isRefreshing = viewModel.refreshing.collectAsState().value
    val pullToRefreshState = rememberPullToRefreshState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val hasConsumedLaunchCreate = rememberSaveable { mutableStateOf(false) }
    val removingWorktreeIds = viewModel.removingWorktreeIds.collectAsState().value
    val missingCwds = viewModel.missingCwds.collectAsState().value
    val showWorktreeSheet = rememberSaveable { mutableStateOf(false) }
    val toolbarExpanded = rememberSaveable { mutableStateOf(true) }
    val creatingWorktree = viewModel.creatingWorktree.collectAsState().value
    val worktreeError = viewModel.worktreeError.collectAsState().value
    val pendingWorktreeRemoval = remember { mutableStateOf<SessionListEvent.ConfirmRemoveWorktree?>(null) }
    return SessionListState(
        rows =
            SessionRowsUi(
                sessions = sessionRows.rows,
                presence =
                    RowPresence(
                        live = liveSessionIds,
                        turnStatuses = turnStatuses,
                        activityUpdatedAt = activityUpdatedAt,
                        closable = closableSessionIds,
                    ),
                cacheRead = sessionRows.loaded,
                missingCwds = missingCwds,
                supportsSessionList = supportsSessionList,
                supportsSessionDelete = supportsSessionDelete,
            ),
        connection =
            ConnectionUi(
                connectionState = connectionState,
                connectionDiagnostics = connectionDiagnostics,
                showConnectionStatusDialog = showConnectionStatusDialog,
                pendingAuthentication = pendingAuthentication,
                selectedAuthMethodId = selectedAuthMethodId,
                envValues = envValues,
            ),
        screen =
            ScreenUi(
                snackbarHostState = snackbarHostState,
                serverName = serverName,
                scrollBehavior = scrollBehavior,
                isRefreshing = isRefreshing,
                pullToRefreshState = pullToRefreshState,
                hasConsumedLaunchCreate = hasConsumedLaunchCreate,
            ),
        project =
            ProjectUi(
                currentCwd = currentCwd,
                recentCwds = recentCwds,
                projects = projects,
                showCwdDialog = showCwdDialog,
                cwdDialogValue = cwdDialogValue,
            ),
        worktree =
            WorktreeUi(
                worktrees = worktrees,
                removingWorktreeIds = removingWorktreeIds,
                creatingWorktree = creatingWorktree,
                worktreeError = worktreeError,
                showWorktreeSheet = showWorktreeSheet,
                pendingWorktreeRemoval = pendingWorktreeRemoval,
            ),
        repoScope =
            ScopeUi(
                scope = scope,
                scopeOptions = scopeOptions,
                scopeEnabled = worktrees != null && !currentCwd.isNullOrBlank(),
                toolbarExpanded = toolbarExpanded,
            ),
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
                    state.project.showCwdDialog.value = false
                    state.worktree.showWorktreeSheet.value = false
                    onNavigateToChat(
                        event.sessionId,
                        event.cwd,
                        event.updatedAt,
                        event.title,
                    )
                }

                is SessionListEvent.ShowError -> snackbarHostState.showSnackbar(event.message)

                is SessionListEvent.ConfirmRemoveWorktree -> state.worktree.pendingWorktreeRemoval.value = event
            }
        }
    }

    LaunchedEffect(openCreateSessionDialogOnLaunch) {
        if (openCreateSessionDialogOnLaunch && !hasConsumedLaunchCreate.value) {
            hasConsumedLaunchCreate.value = true
            if (state.project.currentCwd.isNullOrBlank()) {
                state.project.cwdDialogValue.value = state.project.currentCwd.orEmpty()
                viewModel.setPendingCreateAfterCwd()
                state.project.showCwdDialog.value = true
            } else {
                viewModel.createSessionInScope()
            }
        }
    }
}
