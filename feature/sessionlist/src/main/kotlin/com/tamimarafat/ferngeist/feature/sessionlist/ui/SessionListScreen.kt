package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.annotations.UnstableApi
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.common.ui.ConnectionStatusPill
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.ServerNameSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.SessionSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.SessionTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.max

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
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    viewModel: SessionListViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val state = rememberSessionListState(viewModel, navArgName, loadedName)
    val currentCwd = state.currentCwd

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
        cwdDialogValue = state.cwdDialogValue.value,
        currentCwd = currentCwd,
        onCwdDialogValueChange = { state.cwdDialogValue.value = it },
        onDismissCwdDialog = { state.showCwdDialog.value = false },
        updateCurrentCwd = { viewModel.updateCurrentCwd(it) },
        removeRecentCwd = viewModel::removeRecentCwd,
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
        onShowConnectionStatusDialog = { state.showConnectionStatusDialog.value = true },
        onNavigateBack = onNavigateBack,
        onNavigateToChat = onNavigateToChat,
        onCloseSession = viewModel::closeSession,
        createSession = {
            if (currentCwd.isNullOrBlank()) {
                state.cwdDialogValue.value = currentCwd.orEmpty()
                viewModel.setPendingCreateAfterCwd()
                state.showCwdDialog.value = true
            } else {
                viewModel.createSessionWithCurrentCwd()
            }
        },
        onRefresh = { viewModel.refreshSessions(isUserInitiated = true) },
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
    )
}

private class SessionListState(
    val sessions: List<SessionSummary>,
    val liveSessionIds: Set<String>,
    val isLoading: Boolean,
    val currentCwd: String?,
    val connectionState: ChatConnectionState,
    val connectionDiagnostics: ChatConnectionDiagnostics,
    val pendingAuthentication: SessionListPendingAuthentication?,
    val snackbarHostState: SnackbarHostState,
    val recentCwds: List<String>,
    val showCwdDialog: MutableState<Boolean>,
    val cwdDialogValue: MutableState<String>,
    val showConnectionStatusDialog: MutableState<Boolean>,
    val selectedAuthMethodId: MutableState<String?>,
    val envValues: MutableMap<String, String>,
    val isRefreshing: Boolean,
    val pullToRefreshState: PullToRefreshState,
    val supportsSessionList: Boolean,
    val serverName: String,
    val cwdAlpha: Float,
    val scrollBehavior: TopAppBarScrollBehavior,
    val hasConsumedLaunchCreate: MutableState<Boolean>,
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
    UnstableApi::class,
)
@Composable
private fun rememberSessionListState(
    viewModel: SessionListViewModel,
    navArgName: String?,
    loadedName: String?,
): SessionListState {
    val sessionSettings by viewModel.sessionSettings.collectAsState()
    val agentCapabilities by viewModel.agentCapabilities.collectAsState()
    val pendingAuthentication by viewModel.pendingAuthentication.collectAsState()
    val currentCwd = sessionSettings.cwd
    val serverName =
        resolveServerDisplayName(
            navArgName,
            loadedName,
            stringResource(R.string.sessionlist_topbar_title),
        )
    val supportsSessionList = agentCapabilities?.sessionCapabilities?.list != null
    val cwdAlpha by animateFloatAsState(
        targetValue = if (!currentCwd.isNullOrBlank()) 1f else 0f,
        label = "cwdAlpha",
    )
    return SessionListState(
        sessions = viewModel.sessions.collectAsState().value,
        liveSessionIds = viewModel.liveSessionIds.collectAsState().value,
        isLoading = viewModel.isLoading.collectAsState().value,
        currentCwd = currentCwd,
        connectionState = viewModel.connectionState.collectAsState().value,
        connectionDiagnostics = viewModel.connectionDiagnostics.collectAsState().value,
        pendingAuthentication = pendingAuthentication,
        snackbarHostState = remember { SnackbarHostState() },
        recentCwds = viewModel.recentCwds.collectAsState().value,
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
        serverName = serverName,
        cwdAlpha = cwdAlpha,
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
        hasConsumedLaunchCreate = rememberSaveable { mutableStateOf(false) },
    )
}

@Composable
private fun SessionListEventEffects(
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

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionListEvent.NavigateToChat -> {
                    onNavigateToChat(
                        event.sessionId,
                        event.cwd,
                        event.updatedAt,
                        event.title,
                    )
                }

                is SessionListEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
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
    onShowCwdDialog: () -> Unit,
    onShowConnectionStatusDialog: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    onCloseSession: (String) -> Unit,
    createSession: () -> Unit,
    onRefresh: () -> Unit,
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

    Box(
        modifier = Modifier.sessionListContainer(state, onRefresh),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                SessionListTopBar(
                    state = state,
                    serverId = serverId,
                    currentCwd = currentCwd,
                    titleStyle = titleStyle,
                    onShowCwdDialog = onShowCwdDialog,
                    onShowConnectionStatusDialog = onShowConnectionStatusDialog,
                    onNavigateBack = onNavigateBack,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
            },
            floatingActionButton = {
                SessionListFab(onClick = createSession)
            },
            snackbarHost = { SnackbarHost(state.snackbarHostState) },
        ) { padding ->
            SessionListContent(
                padding = padding,
                state = state,
                onNavigateToChat = onNavigateToChat,
                onCloseSession = onCloseSession,
                createSession = createSession,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
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

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun SessionListTopBar(
    state: SessionListState,
    serverId: String,
    currentCwd: String?,
    titleStyle: TextStyle,
    onShowCwdDialog: () -> Unit,
    onShowConnectionStatusDialog: () -> Unit,
    onNavigateBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    TwoRowsTopAppBar(
        title = { expanded ->
            SessionListTopBarTitle(
                expanded = expanded,
                collapsedFraction =
                    state.scrollBehavior.state.collapsedFraction
                        .coerceIn(0f, 1f),
                serverId = serverId,
                serverName = state.serverName,
                titleStyle = titleStyle,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        },
        subtitle = { expanded ->
            if (expanded) {
                SessionListTopBarSubtitle(state.cwdAlpha, currentCwd)
            }
        },
        navigationIcon = {
            FilledTonalIconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription =
                        stringResource(
                            R.string.sessionlist_back_desc,
                        ),
                )
            }
        },
        actions = {
            SessionListTopBarActions(
                state = state,
                onShowCwdDialog = onShowCwdDialog,
                onShowConnectionStatusDialog = onShowConnectionStatusDialog,
            )
        },
        collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
        expandedHeight = TopAppBarDefaults.LargeAppBarExpandedHeight,
        scrollBehavior = state.scrollBehavior,
    )
}

@Composable
private fun SessionListTopBarSubtitle(
    cwdAlpha: Float,
    currentCwd: String?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .heightIn(min = 20.dp)
                .alpha(cwdAlpha),
    ) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = stringResource(R.string.sessionlist_cwd_desc),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = currentCwd.orEmpty(),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListTopBarActions(
    state: SessionListState,
    onShowCwdDialog: () -> Unit,
    onShowConnectionStatusDialog: () -> Unit,
) {
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Above,
            ),
        tooltip = {
            PlainTooltip {
                Text(
                    stringResource(
                        R.string.sessionlist_cwd_tooltip,
                    ),
                )
            }
        },
        state = rememberTooltipState(),
    ) {
        FilledTonalIconButton(
            onClick = onShowCwdDialog,
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription =
                    stringResource(
                        R.string.sessionlist_cwd_desc,
                    ),
            )
        }
    }
    ConnectionStatusPill(
        connectionState = state.connectionState,
        onClick = onShowConnectionStatusDialog,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription =
                stringResource(
                    R.string.sessionlist_new_session_desc,
                ),
        )
    }
}

@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun SessionListContent(
    padding: PaddingValues,
    state: SessionListState,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    onCloseSession: (String) -> Unit,
    createSession: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    when {
        state.isLoading && state.sessions.isEmpty() -> {
            SessionListLoadingContent(padding)
        }

        state.sessions.isEmpty() -> {
            SessionListEmptyContent(
                padding = padding,
                supportsSessionList = state.supportsSessionList,
                onCreateSession = createSession,
            )
        }

        else -> {
            val groupedSessions = groupSessionsByDate(state.sessions)
            SessionListLazyColumn(
                groupedSessions = groupedSessions,
                sessionCount = state.sessions.size,
                padding = padding,
                liveSessionIds = state.liveSessionIds,
                onCloseSession = onCloseSession,
                onNavigateToChat = onNavigateToChat,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        }
    }
}

@Composable
private fun SessionListLoadingContent(padding: PaddingValues) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(
            modifier = Modifier.size(64.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionListEmptyContent(
    padding: PaddingValues,
    supportsSessionList: Boolean,
    onCreateSession: () -> Unit,
) {
    EmptySessionList(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        supportsSessionList = supportsSessionList,
        onCreateSession = onCreateSession,
    )
}

@Composable
private fun groupSessionsByDate(sessions: List<SessionSummary>): Map<String, List<SessionSummary>> {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    val locale = LocalLocale.current
    val dateFormatter =
        remember(locale) {
            SimpleDateFormat("MMMM d, yyyy", locale.platformLocale)
        }
    val sortedSessions =
        sessions.sortedWith(
            compareByDescending<SessionSummary> { it.updatedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.id },
        )
    val groupedSessions = linkedMapOf<String, List<SessionSummary>>()
    // Group sessions by calendar date, sorted newest-first.
    // Sessions with updatedAt are bucketed by local date; those without
    // go into a final "Unknown" bucket.
    val withDate =
        sortedSessions.filter { it.updatedAt != null }.groupBy { session ->
            val updatedAt = session.updatedAt ?: 0L
            Instant.ofEpochMilli(updatedAt).atZone(zoneId).toLocalDate()
        }
    withDate.entries
        .sortedByDescending { it.key }
        .forEach { (sessionDate, groupSessions) ->
            // Show "Today" / "Yesterday" for recent dates, formatted date otherwise.
            val label =
                when (sessionDate) {
                    today ->
                        stringResource(
                            R.string.sessionlist_today,
                        )
                    today.minusDays(
                        1,
                    ),
                    ->
                        stringResource(
                            R.string.sessionlist_yesterday,
                        )
                    else -> {
                        val epoch =
                            max(
                                groupSessions.firstOrNull()?.updatedAt ?: 0L,
                                0L,
                            )
                        dateFormatter.format(Date(epoch))
                    }
                }
            groupedSessions[label] = groupSessions
        }
    val unknownSessions = sortedSessions.filter { it.updatedAt == null }
    if (unknownSessions.isNotEmpty()) {
        groupedSessions[
            stringResource(
                R.string.sessionlist_unknown_date,
            ),
        ] =
            unknownSessions
    }
    return groupedSessions
}

@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun SessionListLazyColumn(
    groupedSessions: Map<String, List<SessionSummary>>,
    sessionCount: Int,
    padding: PaddingValues,
    liveSessionIds: Set<String>,
    onCloseSession: (String) -> Unit,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groupedSessions.forEach { (group, groupSessions) ->
            item {
                Text(
                    text = group,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(groupSessions, key = { it.id }) { session ->
                SessionCard(
                    session = session,
                    isLive = session.id in liveSessionIds,
                    onLongPress = { onCloseSession(session.id) },
                    onClick = {
                        onNavigateToChat(
                            session.id,
                            session.cwd ?: "",
                            session.updatedAt,
                            session.title,
                        )
                    },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
            }
        }
        item {
            Text(
                text =
                    LocalResources.current.getQuantityString(
                        R.plurals.sessionlist_session_count,
                        sessionCount,
                        sessionCount,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SessionListOverlays(
    showCwdDialog: Boolean,
    recentCwds: List<String>,
    sessions: List<SessionSummary>,
    cwdDialogValue: String,
    currentCwd: String?,
    onCwdDialogValueChange: (String) -> Unit,
    onDismissCwdDialog: () -> Unit,
    updateCurrentCwd: (String) -> Unit,
    removeRecentCwd: (String) -> Unit,
    showConnectionStatusDialog: Boolean,
    connectionState: ChatConnectionState,
    connectionDiagnostics: ChatConnectionDiagnostics,
    onDismissConnectionStatusDialog: () -> Unit,
    pendingAuthentication: SessionListPendingAuthentication?,
    selectedAuthMethodId: String?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envValues: MutableMap<String, String>,
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismissAuthentication: () -> Unit,
) {
    if (showCwdDialog) {
        SessionCwdDialog(
            recentCwds = recentCwds,
            sessions = sessions,
            cwdDialogValue = cwdDialogValue,
            currentCwd = currentCwd,
            onCwdDialogValueChange = onCwdDialogValueChange,
            onDismiss = onDismissCwdDialog,
            updateCurrentCwd = updateCurrentCwd,
            removeRecentCwd = removeRecentCwd,
        )
    }

    if (showConnectionStatusDialog) {
        SessionConnectionStatusDialog(
            connectionState = connectionState,
            diagnostics = connectionDiagnostics,
            onDismiss = onDismissConnectionStatusDialog,
        )
    }

    pendingAuthentication?.let { pending ->
        PendingAuthenticationDialog(
            pendingAuthentication = pending,
            selectedAuthMethodId = selectedAuthMethodId,
            onSelectedAuthMethodChange = onSelectedAuthMethodChange,
            envValues = envValues,
            onSubmit = onSubmit,
            onReconnect = onReconnect,
            onDismiss = onDismissAuthentication,
        )
    }
}

@Composable
private fun SessionCwdDialog(
    recentCwds: List<String>,
    sessions: List<SessionSummary>,
    cwdDialogValue: String,
    currentCwd: String?,
    onCwdDialogValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    updateCurrentCwd: (String) -> Unit,
    removeRecentCwd: (String) -> Unit,
) {
    CwdDialog(
        recentCwds = recentCwds,
        sessions = sessions,
        cwdDialogValue = cwdDialogValue,
        onCwdDialogValueChange = onCwdDialogValueChange,
        onSave = {
            onDismiss()
            updateCurrentCwd(cwdDialogValue)
        },
        onClear =
            if (currentCwd != null) {
                {
                    onDismiss()
                    updateCurrentCwd("")
                }
            } else {
                null
            },
        onDismiss = onDismiss,
        onRemoveRecentCwd = removeRecentCwd,
    )
}

@Composable
private fun SessionConnectionStatusDialog(
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    onDismiss: () -> Unit,
) {
    ConnectionDiagnosticsDialog(
        connectionState = connectionState,
        diagnostics = diagnostics,
        onDismiss = onDismiss,
    )
}

@Composable
private fun PendingAuthenticationDialog(
    pendingAuthentication: SessionListPendingAuthentication,
    selectedAuthMethodId: String?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envValues: MutableMap<String, String>,
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()
    val selectedMethod =
        pendingAuthentication.authMethods.firstOrNull { it.id == selectedAuthMethodId }
            ?: pendingAuthentication.authMethods.firstOrNull()
    val isGatewayEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.gatewayRuntimeId != null
    val isManualEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.gatewayRuntimeId == null
    // All non-optional env vars must be filled before the button is enabled.
    val requiredEnvVarsFilled =
        selectedMethod
            ?.envVars
            ?.all { envVar -> envVar.optional || !envValues[envVar.name].isNullOrBlank() }
            ?: false

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.sessionlist_auth_title,
                    pendingAuthentication.serverName,
                ),
            )
        },
        text = {
            SessionAuthDialogBody(
                pendingAuthentication = pendingAuthentication,
                selectedMethod = selectedMethod,
                onSelectedAuthMethodChange = onSelectedAuthMethodChange,
                envValues = envValues,
                onOpenLink = { uriHandler.openUri(it) },
                onEnvValueChange = { name, value -> envValues[name] = value },
                scrollState = scrollState,
            )
        },
        confirmButton = {
            SessionAuthConfirmButton(
                selectedMethod = selectedMethod,
                isGatewayEnvAuth = isGatewayEnvAuth,
                isManualEnvAuth = isManualEnvAuth,
                requiredEnvVarsFilled = requiredEnvVarsFilled,
                envValues = envValues,
                onReconnect = onReconnect,
                onSubmit = onSubmit,
            )
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.sessionlist_auth_cancel))
            }
        },
    )
}

/**
 * Tappable card for a single session in the list.
 *
 * Uses [sharedBounds] for a shared-element transition to the chat screen,
 * keyed by [SessionSharedBoundsKey] (outer card) and [SessionTitleSharedBoundsKey] (title text).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun RowScope.SessionCardText(
    session: SessionSummary,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    with(sharedTransitionScope) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    session.title
                        ?: stringResource(
                            R.string.sessionlist_untitled,
                        ),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier =
                    Modifier.sharedBounds(
                        sharedContentState =
                            rememberSharedContentState(
                                key = SessionTitleSharedBoundsKey(session.id),
                            ),
                        animatedVisibilityScope = animatedContentScope,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    ),
            )
            session.cwd?.let { cwd ->
                Text(
                    text = cwd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: SessionSummary,
    isLive: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    with(sharedTransitionScope) {
        Card(
            modifier =
                Modifier
                    .sharedBounds(
                        sharedContentState =
                            rememberSharedContentState(
                                key = SessionSharedBoundsKey(session.id),
                            ),
                        animatedVisibilityScope = animatedContentScope,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    ).fillMaxWidth()
                    .clip(CardDefaults.shape)
                    .combinedClickable(onClick = onClick, onLongClick = onLongPress),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionCardText(
                    session = session,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
                if (isLive) {
                    // Live indicator: this session currently holds a gateway connection.
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * Shown when there are no sessions.
 * Text adapts based on whether the agent supports session listing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptySessionList(
    modifier: Modifier = Modifier,
    supportsSessionList: Boolean,
    onCreateSession: () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        ErrorStateCard(
            headline = stringResource(R.string.sessionlist_empty_title),
            body =
                if (supportsSessionList) {
                    stringResource(
                        R.string.sessionlist_empty_subtitle_listed,
                    )
                } else {
                    stringResource(
                        R.string.sessionlist_empty_subtitle_offline,
                    )
                },
            icon = Icons.Rounded.Forum,
            medallionContainer = MaterialTheme.colorScheme.primaryContainer,
            medallionContent = MaterialTheme.colorScheme.onPrimaryContainer,
            medallionShape = MaterialShapes.Cookie9Sided.toShape(),
            ctaLabel = stringResource(R.string.sessionlist_empty_action),
            onCta = onCreateSession,
            ctaIsHero = true,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Empty session list", showBackground = true)
@Composable
private fun EmptySessionListPreview() {
    EmptySessionList(
        supportsSessionList = true,
        onCreateSession = {},
    )
}

/**
 * Renders the session list top-bar title with shared-element transition ownership.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SessionListTopBarTitle(
    expanded: Boolean,
    collapsedFraction: Float,
    serverId: String,
    serverName: String,
    titleStyle: TextStyle,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val sharedContentState =
        with(sharedTransitionScope) {
            rememberSharedContentState(key = ServerNameSharedBoundsKey(serverId))
        }
    val ownsSharedTitleBounds =
        if (expanded) {
            collapsedFraction < 0.5f
        } else {
            collapsedFraction >= 0.5f
        }
    val baseModifier =
        if (ownsSharedTitleBounds) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = sharedContentState,
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                )
            }
        } else {
            Modifier
        }

    Text(
        text = serverName,
        style = titleStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = baseModifier,
    )
}

/**
 * Resolves the display name for the session list top bar.
 *
 * Priority order:
 * 1. [loadedName] if non-blank (server-provided name from the session list response)
 * 2. [navArgName] if non-blank (fallback name passed as a navigation argument)
 * 3. "Sessions" (default)
 */
internal fun resolveServerDisplayName(
    navArgName: String?,
    loadedName: String?,
    defaultName: String = "Sessions",
): String {
    if (!loadedName.isNullOrBlank()) return loadedName
    if (!navArgName.isNullOrBlank()) return navArgName
    return defaultName
}
