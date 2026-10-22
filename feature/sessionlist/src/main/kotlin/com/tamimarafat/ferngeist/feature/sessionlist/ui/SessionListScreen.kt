package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.agentclientprotocol.annotations.UnstableApi
import com.tamimarafat.ferngeist.core.common.ui.ServerSharedBoundsKey
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.projectRoot
import kotlinx.coroutines.delay

/**
 * Full-screen session list for an ACP agent server.
 *
 * Shows sessions grouped by date ("Today", "Yesterday", formatted date, "Unknown").
 * Supports pull-to-refresh (when the agent advertises session listing capability),
 * a scroll-responsive title (lerps between headlineMedium and titleLarge),
 * and a FAB to create new sessions.
 *
 * Three content states: loading spinner → empty state → grouped session cards.
 */
private const val SESSION_LIST_POLL_MS = 30_000L

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
    UnstableApi::class,
)
@Composable
fun SessionListScreen(
    navArgName: String?,
    loadedName: String?,
    serverId: String,
    openCreateSessionDialogOnLaunch: Boolean = false,
    showBackButton: Boolean = true,
    sharedBoundsEnabled: Boolean = true,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    viewModel: SessionListViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val state = rememberSessionListState(viewModel, navArgName, loadedName)
    val currentCwd = state.currentCwd

    // The gateway has no change feed, so sessions created or advanced elsewhere (another
    // device, the CLI) only arrive by re-listing: on every resume, then on a slow poll while
    // the list is on screen. The cache still paints first, and a background listing is silent.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // The server list's launch just listed; the first pass after it must not launch again.
                viewModel.refreshSessions(reuseRecent = true)
                viewModel.refreshWorktrees()
                delay(SESSION_LIST_POLL_MS)
            }
        }
    }

    SessionListEventEffects(
        viewModel = viewModel,
        state = state,
        pendingAuthentication = state.pendingAuthentication,
        snackbarHostState = state.snackbarHostState,
        envValues = state.envValues,
        selectedAuthMethodId = state.selectedAuthMethodId,
        hasConsumedLaunchCreate = state.hasConsumedLaunchCreate,
        openCreateSessionDialogOnLaunch = openCreateSessionDialogOnLaunch,
        onNavigateToChat = onNavigateToChat,
    )

    SessionListOverlays(
        showCwdDialog = state.showCwdDialog.value,
        recentCwds = state.recentCwds,
        sessions = state.sessions,
        cwdRootOf =
            remember(currentCwd, state.scopeOptions, state.worktrees) {
                { cwd: String ->
                    projectRoot(cwd, state.worktrees.orEmpty())
                }
            },
        cwdDialogValue = state.cwdDialogValue.value,
        currentCwd = currentCwd,
        onCwdDialogValueChange = { state.cwdDialogValue.value = it },
        onDismissCwdDialog = { state.showCwdDialog.value = false },
        updateCurrentCwd = { viewModel.updateCurrentCwd(it) },
        removeRecentCwd = viewModel::removeRecentCwd,
        showWorktreeSheet = state.showWorktreeSheet.value,
        scopeOptions = state.scopeOptions,
        initialBase =
            (state.scope as? WorktreeScope.Worktree)
                ?.let { picked ->
                    state.scopeOptions
                        .firstOrNull { isSameCwd(it.path, picked.path) }
                        ?.worktree
                        ?.branch
                }.orEmpty(),
        loadRefs = viewModel::repoRefs,
        worktreeError = state.worktreeError,
        creatingWorktree = state.creatingWorktree,
        onWorktreeFormChanged = viewModel::clearWorktreeError,
        onDismissWorktreeSheet = {
            state.showWorktreeSheet.value = false
            viewModel.clearWorktreeError()
        },
        onCreateWorktreeSession = viewModel::createSessionInNewWorktree,
        pendingWorktreeRemoval = state.pendingWorktreeRemoval.value,
        onDismissWorktreeRemoval = { state.pendingWorktreeRemoval.value = null },
        onConfirmWorktreeRemoval = { pending ->
            state.pendingWorktreeRemoval.value = null
            viewModel.removeWorktree(pending)
        },
        showConnectionStatusDialog = state.showConnectionStatusDialog.value,
        connectionState = state.connectionState,
        connectionDiagnostics = state.connectionDiagnostics,
        onDismissConnectionStatusDialog = { state.showConnectionStatusDialog.value = false },
        pendingAuthentication = state.pendingAuthentication,
        selectedAuthMethodId = state.selectedAuthMethodId.value,
        onSelectedAuthMethodChange = { state.selectedAuthMethodId.value = it },
        envValues = state.envValues,
        onSubmit = { methodId, values -> viewModel.authenticate(methodId, values) },
        onReconnect = viewModel::reconnectPendingAuthentication,
        onDismissAuthentication = viewModel::dismissAuthenticationPrompt,
    )

    SessionListScaffold(
        state = state,
        serverId = serverId,
        currentCwd = currentCwd,
        onShowCwdDialog = {
            state.cwdDialogValue.value = currentCwd.orEmpty()
            state.showCwdDialog.value = true
        },
        onSelectRepo = viewModel::updateCurrentCwd,
        onShowConnectionStatusDialog = { state.showConnectionStatusDialog.value = true },
        showBackButton = showBackButton,
        sharedBoundsEnabled = sharedBoundsEnabled,
        onNavigateBack = onNavigateBack,
        onNavigateToChat = onNavigateToChat,
        onRemoveWorktree = viewModel::requestRemoveWorktree,
        onCloseSession = viewModel::closeSession,
        onDeleteSession = viewModel::deleteSession,
        createSession = {
            if (currentCwd.isNullOrBlank()) {
                state.cwdDialogValue.value = currentCwd.orEmpty()
                viewModel.setPendingCreateAfterCwd()
                state.showCwdDialog.value = true
            } else {
                viewModel.createSessionInScope()
            }
        },
        createWorktreeSession = {
            viewModel.clearWorktreeError()
            state.showWorktreeSheet.value = true
        },
        onSelectScope = viewModel::selectScope,
        onRefresh = { viewModel.refreshSessions(isUserInitiated = true) },
        onChatOpened = viewModel::onChatOpened,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun SessionListScaffold(
    state: SessionListState,
    serverId: String,
    currentCwd: String?,
    showBackButton: Boolean,
    sharedBoundsEnabled: Boolean,
    onShowCwdDialog: () -> Unit,
    onSelectRepo: (String) -> Unit,
    onShowConnectionStatusDialog: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    onRemoveWorktree: (sessionId: String?, worktreeId: String) -> Unit,
    onCloseSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    createSession: () -> Unit,
    createWorktreeSession: () -> Unit,
    onSelectScope: (WorktreeScope) -> Unit,
    onRefresh: () -> Unit,
    onChatOpened: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val collapse =
        state.scrollBehavior.state.collapsedFraction
            .coerceIn(0f, 1f)
    // Interpolate title size between expanded (headlineMedium) and collapsed (titleLarge)
    // as the user scrolls — gives a smooth visual transition in the top bar.
    val titleStyle =
        lerp(
            MaterialTheme.typography.headlineMedium, // expanded
            MaterialTheme.typography.titleLarge, // collapsed
            collapse,
        )

    // The whole list grows out of the agent card it was opened from. Not in the workspace: the
    // card stays on screen beside this pane, and two live claimants scale to each other's bounds.
    val transitionModifier =
        if (sharedBoundsEnabled) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = ServerSharedBoundsKey(serverId)),
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                )
            }
        } else {
            Modifier
        }

    Box(
        modifier =
            transitionModifier
                .sessionListContainer(state, onRefresh)
                .floatingToolbarVerticalNestedScroll(
                    expanded = state.toolbarExpanded.value,
                    onExpand = { state.toolbarExpanded.value = true },
                    onCollapse = { state.toolbarExpanded.value = false },
                ).background(MaterialTheme.colorScheme.surface),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                SessionListTopBar(
                    state = state,
                    serverId = serverId,
                    titleStyle = titleStyle,
                    onShowConnectionStatusDialog = onShowConnectionStatusDialog,
                    showBackButton = showBackButton,
                    sharedBoundsEnabled = sharedBoundsEnabled,
                    onNavigateBack = onNavigateBack,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
            },
            floatingActionButton = {
                WorktreeToolbar(
                    expanded = state.toolbarExpanded.value,
                    repo = currentCwd,
                    projects = state.projects,
                    worktreesEnabled = state.scopeEnabled,
                    scope = state.scope,
                    options = state.scopeOptions,
                    onSelectRepo = onSelectRepo,
                    onOtherProject = onShowCwdDialog,
                    onSelectScope = onSelectScope,
                    onNewChat = createSession,
                    onNewWorktree = createWorktreeSession,
                    onRemoveWorktree = { onRemoveWorktree(null, it) },
                )
            },
            snackbarHost = { SnackbarHost(state.snackbarHostState) },
        ) { padding ->
            SessionListContent(
                padding = padding,
                state = state,
                onNavigateToChat = onNavigateToChat,
                onRemoveWorktree = onRemoveWorktree,
                onCloseSession = onCloseSession,
                onDeleteSession = onDeleteSession,
                createSession = createSession,
                onChatOpened = onChatOpened,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                sharedBoundsEnabled = sharedBoundsEnabled,
            )
        }

        SessionListRefreshIndicator(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.sessionListContainer(
    state: SessionListState,
    onRefresh: () -> Unit,
): Modifier =
    if (state.supportsSessionList) {
        this
            .fillMaxSize()
            .nestedScroll(state.scrollBehavior.nestedScrollConnection)
            .pullToRefresh(
                state = state.pullToRefreshState,
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
            )
    } else {
        this
            .fillMaxSize()
            .nestedScroll(state.scrollBehavior.nestedScrollConnection)
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.SessionListRefreshIndicator(state: SessionListState) {
    if (state.supportsSessionList) {
        PullToRefreshDefaults.LoadingIndicator(
            state = state.pullToRefreshState,
            isRefreshing = state.isRefreshing,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
        )
    }
}
