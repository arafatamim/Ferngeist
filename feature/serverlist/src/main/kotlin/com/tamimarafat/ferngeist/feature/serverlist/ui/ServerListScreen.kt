package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.feature.serverlist.PendingAuthentication
import com.tamimarafat.ferngeist.feature.serverlist.PendingLaunchConsent
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.RecentSession
import com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine

private const val DISABLED_MENU_ALPHA = 0.38f

/** Quick chat's sheet: projects, agents, their listing statuses, start (agent, cwd), dismiss. */
typealias QuickChatSheetSlot = @Composable (
    List<ProjectSummary>,
    List<LaunchableTarget.GatewayAgent>,
    Map<String, GatewayProjectLine.Agent>,
    (LaunchableTarget.GatewayAgent, String) -> Unit,
    () -> Unit,
) -> Unit

private data class ServerListScreenState(
    val servers: List<LaunchableTarget>,
    val hasGateways: Boolean,
    val uiState: ServerListUiState,
    val isLoading: Boolean,
    val recentSessions: List<RecentSession>,
    val projects: List<ProjectSummary>,
    val snackbarHostState: SnackbarHostState,
    val pendingAuthentication: PendingAuthentication?,
    val pendingLaunchConsent: PendingLaunchConsent?,
    val liveServerIds: Set<String>,
    val connectedChatKeys: Set<String>,
    val turnStatuses: Map<String, TurnActivity>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberServerListState(viewModel: ServerListViewModel): ServerListScreenState {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val hasGateways by viewModel.hasGateways.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()
    val liveServerIds by viewModel.liveServerIds.collectAsStateWithLifecycle()
    val connectedChatKeys by viewModel.connectedChatKeys.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val turnStatuses by viewModel.turnStatuses.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    return ServerListScreenState(
        servers = servers,
        hasGateways = hasGateways,
        uiState = uiState,
        isLoading = isLoading,
        recentSessions = recentSessions,
        projects = projects,
        snackbarHostState = snackbarHostState,
        pendingAuthentication = uiState.pendingAuthentication,
        pendingLaunchConsent = uiState.pendingLaunchConsent,
        liveServerIds = liveServerIds,
        connectedChatKeys = connectedChatKeys,
        turnStatuses = turnStatuses,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun ServerListScreen(
    onNavigateToAddServer: () -> Unit,
    onNavigateToPairGateway: () -> Unit,
    onNavigateToGateways: () -> Unit,
    onNavigateToEditServer: (LaunchableTarget) -> Unit,
    onNavigateToSessions: (String, String, List<SessionSummary>, Boolean) -> Unit,
    onResumeSession: (RecentSession) -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
    // The project, worktree and agent sheets live with the project screen; the app hands them in.
    quickChatSheet: QuickChatSheetSlot,
    viewModel: ServerListViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    onHomeScreenReady: (Boolean) -> Unit = {},
) {
    val screenState = rememberServerListState(viewModel)
    val servers = screenState.servers
    val hasGateways = screenState.hasGateways
    val uiState = screenState.uiState
    val isLoading = screenState.isLoading
    val recentSessions = screenState.recentSessions
    val snackbarHostState = screenState.snackbarHostState
    var showAddMenu by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showQuickChat by rememberSaveable { mutableStateOf(false) }
    val refreshingGateways by viewModel.refreshing.collectAsStateWithLifecycle()
    val agentStatuses by viewModel.agentStatuses.collectAsStateWithLifecycle()
    val projectDiscoveryAllowed by viewModel.projectDiscoveryAllowed.collectAsStateWithLifecycle()
    val quickChatAvailable =
        screenState.projects.isNotEmpty() && servers.any { it is LaunchableTarget.GatewayAgent }

    // Hold the launch splash until the agent list has data and has drawn it. Waiting
    // a frame after the load flag clears means the frame the splash hands over to is
    // already the populated list, not a blank scaffold that fills in a beat later.
    // Entering home, including back from a chat, is a projects fetch trigger.
    LaunchedEffect(projectDiscoveryAllowed) {
        if (projectDiscoveryAllowed) viewModel.refreshProjects()
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            withFrameNanos { }
            onHomeScreenReady(true)
        }
    }

    ServerListDialogsAndEffects(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        showAboutDialog = showAboutDialog,
        onDismissAbout = { showAboutDialog = false },
        onNavigateToSessions = onNavigateToSessions,
        onQuickChat = onResumeSession,
    )

    BackHandler(showAddMenu) {
        showAddMenu = false
    }

    ServerListScaffold(
        snackbarHostState = snackbarHostState,
        showAddMenu = showAddMenu,
        onShowAddMenuChange = { showAddMenu = it },
        hasGateways = hasGateways,
        quickChatAvailable = quickChatAvailable,
        onAboutClick = { showAboutDialog = true },
        onQuickChat = {
            showAddMenu = false
            showQuickChat = true
        },
        refreshing = refreshingGateways.isNotEmpty(),
        onRefresh = { viewModel.refreshProjects(force = true) },
        onNavigateToGateways = onNavigateToGateways,
        onNavigateToPairGateway = onNavigateToPairGateway,
        onNavigateToAddServer = onNavigateToAddServer,
        isLoading = isLoading,
        servers = servers,
        recentSessions = recentSessions,
        projects = screenState.projects.filter { it.chats.isNotEmpty() },
        onOpenProject = onOpenProject,
        uiState = uiState,
        liveServerIds = screenState.liveServerIds,
        connectedChatKeys = screenState.connectedChatKeys,
        turnStatuses = screenState.turnStatuses,
        onResumeSession = onResumeSession,
        onConnect = { viewModel.connectAndOpenServer(it) },
        onEdit = onNavigateToEditServer,
        onDelete = { viewModel.deleteServer(it) },
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
    )
    if (showQuickChat && quickChatAvailable) {
        quickChatSheet(
            screenState.projects,
            servers.filterIsInstance<LaunchableTarget.GatewayAgent>(),
            agentStatuses,
            { target, cwd ->
                showQuickChat = false
                viewModel.startQuickChat(target, cwd)
            },
            { showQuickChat = false },
        )
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun ServerListScaffold(
    snackbarHostState: SnackbarHostState,
    showAddMenu: Boolean,
    onShowAddMenuChange: (Boolean) -> Unit,
    onAboutClick: () -> Unit,
    onQuickChat: () -> Unit,
    hasGateways: Boolean,
    quickChatAvailable: Boolean,
    onNavigateToGateways: () -> Unit,
    onNavigateToPairGateway: () -> Unit,
    onNavigateToAddServer: () -> Unit,
    isLoading: Boolean,
    servers: List<LaunchableTarget>,
    recentSessions: List<RecentSession>,
    projects: List<ProjectSummary>,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onOpenProject: (ProjectSummary) -> Unit = {},
    uiState: ServerListUiState,
    liveServerIds: Set<String>,
    connectedChatKeys: Set<String>,
    turnStatuses: Map<String, TurnActivity>,
    onResumeSession: (RecentSession) -> Unit,
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    Scaffold(
        // Background surface; the lighter foreground "Your agents" sheet reads as elevated against it.
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            ServerListTopBar(
                collapsed = recentSessions.isNotEmpty() && servers.size > 3,
                onAboutClick = onAboutClick,
            )
        },
        floatingActionButton = {
            AddFabMenu(
                onQuickChat = onQuickChat,
                expanded = showAddMenu,
                onExpandedChange = onShowAddMenuChange,
                hasGateways = hasGateways,
                quickChatAvailable = quickChatAvailable,
                onNavigateToGateways = {
                    onShowAddMenuChange(false)
                    if (hasGateways) {
                        onNavigateToGateways()
                    } else {
                        onNavigateToPairGateway()
                    }
                },
                onNavigateToAddServer = {
                    onShowAddMenuChange(false)
                    onNavigateToAddServer()
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val heroSession = recentSessions.firstOrNull()
        val olderSessions = if (recentSessions.size > 1) recentSessions.drop(1) else emptyList()

        ServerListContent(
            modifier = Modifier.padding(padding),
            isLoading = isLoading,
            servers = servers,
            heroSession = heroSession,
            olderSessions = olderSessions,
            projects = projects,
            refreshing = refreshing,
            onRefresh = onRefresh,
            onOpenProject = onOpenProject,
            uiState = uiState,
            liveServerIds = liveServerIds,
            connectedChatKeys = connectedChatKeys,
            turnStatuses = turnStatuses,
            onResumeSession = onResumeSession,
            onConnect = onConnect,
            onEdit = onEdit,
            onDelete = onDelete,
            onAddServer = onNavigateToAddServer,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
        )
    }
}

@Composable
private fun ServerListDialogsAndEffects(
    viewModel: ServerListViewModel,
    snackbarHostState: SnackbarHostState,
    uiState: ServerListUiState,
    showAboutDialog: Boolean,
    onDismissAbout: () -> Unit,
    onNavigateToSessions: (String, String, List<SessionSummary>, Boolean) -> Unit,
    onQuickChat: (RecentSession) -> Unit,
) {
    val pendingAuthentication = uiState.pendingAuthentication
    val pendingLaunchConsent = uiState.pendingLaunchConsent
    var selectedAuthMethodId by rememberSaveable(pendingAuthentication?.serverId) {
        mutableStateOf(
            uiState.pendingAuthentication
                ?.authMethods
                ?.firstOrNull()
                ?.id,
        )
    }
    val envValues = remember(pendingAuthentication?.serverId) { mutableStateMapOf<String, String>() }

    ServerListEventEffects(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        pendingAuthentication = pendingAuthentication,
        envValues = envValues,
        onNavigateToSessions = onNavigateToSessions,
        onQuickChat = onQuickChat,
    )

    ServerListDialogsHost(
        pendingAuthentication = pendingAuthentication,
        pendingLaunchConsent = pendingLaunchConsent,
        showAboutDialog = showAboutDialog,
        selectedAuthMethodId = selectedAuthMethodId,
        envValues = envValues,
        onSelectedAuthMethodChange = { methodId -> selectedAuthMethodId = methodId },
        onAuthenticate = { methodId, values ->
            pendingAuthentication?.let { viewModel.authenticate(it.serverId, methodId, values) }
        },
        onReconnect = { pendingAuthentication?.let { viewModel.retryPendingAuthentication(it.serverId) } },
        onDismissAuthentication = viewModel::dismissAuthenticationPrompt,
        onConfirmLaunchConsent = { viewModel.confirmLaunchConsent(it) },
        onDismissLaunchConsent = viewModel::dismissLaunchConsent,
        onDismissAbout = onDismissAbout,
    )
}

@Composable
private fun ServerListEventEffects(
    viewModel: ServerListViewModel,
    snackbarHostState: SnackbarHostState,
    uiState: ServerListUiState,
    pendingAuthentication: PendingAuthentication?,
    envValues: MutableMap<String, String>,
    onNavigateToSessions: (String, String, List<SessionSummary>, Boolean) -> Unit,
    onQuickChat: (RecentSession) -> Unit,
) {
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ServerListEvent.NavigateToSessions ->
                    onNavigateToSessions(
                        event.serverId,
                        event.serverName,
                        event.sessions,
                        event.openCreateSessionDialog,
                    )

                is ServerListEvent.StartChat -> onQuickChat(event.session)
                is ServerListEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    LaunchedEffect(uiState.showConnectionError) {
        uiState.showConnectionError?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(pendingAuthentication?.serverId, pendingAuthentication?.persistedEnvValues) {
        envValues.clear()
        pendingAuthentication?.persistedEnvValues?.forEach { (name, value) ->
            envValues[name] = value
        }
    }
}

@Composable
private fun ServerListDialogsHost(
    pendingAuthentication: PendingAuthentication?,
    pendingLaunchConsent: PendingLaunchConsent?,
    showAboutDialog: Boolean,
    selectedAuthMethodId: String?,
    envValues: MutableMap<String, String>,
    onSelectedAuthMethodChange: (String) -> Unit,
    onAuthenticate: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismissAuthentication: () -> Unit,
    onConfirmLaunchConsent: (String) -> Unit,
    onDismissLaunchConsent: () -> Unit,
    onDismissAbout: () -> Unit,
) {
    pendingAuthentication?.let {
        PendingAuthenticationDialog(
            pendingAuthentication = it,
            selectedAuthMethodId = selectedAuthMethodId,
            onSelectedAuthMethodChange = onSelectedAuthMethodChange,
            envValues = envValues,
            onSubmit = onAuthenticate,
            onReconnect = onReconnect,
            onDismiss = onDismissAuthentication,
        )
    }

    pendingLaunchConsent?.let { pending ->
        LaunchRiskConsentDialog(
            pending = pending,
            onConfirm = { onConfirmLaunchConsent(pending.serverId) },
            onDismiss = onDismissLaunchConsent,
        )
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = onDismissAbout)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerListContent(
    modifier: Modifier,
    isLoading: Boolean,
    servers: List<LaunchableTarget>,
    heroSession: RecentSession?,
    olderSessions: List<RecentSession>,
    projects: List<ProjectSummary>,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onOpenProject: (ProjectSummary) -> Unit = {},
    uiState: ServerListUiState,
    liveServerIds: Set<String>,
    connectedChatKeys: Set<String>,
    turnStatuses: Map<String, TurnActivity>,
    onResumeSession: (RecentSession) -> Unit,
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    onAddServer: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize(),
    ) {
        when {
            // The backdrop is not composed until BOTH queries have landed. It seeds its
            // sheet position on a one-shot flag the first time the recents block has a
            // height, and that block has height even while empty (it ends in a spacer).
            // Composing early with no sessions would spend that flag before any session
            // exists, so the single-server expand would never happen.
            isLoading -> ServerListLoadingContent()
            servers.isEmpty() ->
                EmptyServerContent(
                    turnStatuses = turnStatuses,
                    heroSession = heroSession,
                    olderSessions = olderSessions,
                    onResumeSession = onResumeSession,
                    onAddServer = onAddServer,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
            else ->
                AgentsBackdrop(
                    heroSession = heroSession,
                    olderSessions = olderSessions,
                    servers = servers,
                    projects = projects,
                    refreshing = refreshing,
                    onRefresh = onRefresh,
                    onOpenProject = onOpenProject,
                    uiState = uiState,
                    liveServerIds = liveServerIds,
                    connectedChatKeys = connectedChatKeys,
                    turnStatuses = turnStatuses,
                    onResumeSession = onResumeSession,
                    onConnect = onConnect,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerListLoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Default expressive indicator morphs through a shape sequence.
        LoadingIndicator(modifier = Modifier.size(48.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyServerContent(
    turnStatuses: Map<String, TurnActivity>,
    heroSession: RecentSession?,
    olderSessions: List<RecentSession>,
    onResumeSession: (RecentSession) -> Unit,
    onAddServer: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        heroSession?.let { hero ->
            ContinueSessionCard(
                session = hero,
                status = turnStatuses["${hero.serverId}/${hero.sessionId}"] ?: TurnActivity(),
                onClick = { onResumeSession(hero) },
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        olderSessions.forEach { session ->
            RecentSessionCard(
                session = session,
                status = turnStatuses["${session.serverId}/${session.sessionId}"] ?: TurnActivity(),
                onClick = { onResumeSession(session) },
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        EmptyServerList(
            onAddServer = onAddServer,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(96.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    hasGateways: Boolean,
    quickChatAvailable: Boolean,
    onNavigateToGateways: () -> Unit,
    onNavigateToAddServer: () -> Unit,
    onQuickChat: () -> Unit,
) {
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            AddFabToggle(
                expanded = expanded,
                onExpandedChange = onExpandedChange,
            )
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { if (quickChatAvailable) onQuickChat() },
            modifier =
                if (quickChatAvailable) {
                    Modifier.handCursor()
                } else {
                    Modifier.graphicsLayer { alpha = DISABLED_MENU_ALPHA }.semantics { disabled() }
                },
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
            text = { Text(stringResource(R.string.serverlist_quick_chat)) },
        )
        FloatingActionButtonMenuItem(
            onClick = onNavigateToGateways,
            modifier = Modifier.handCursor(),
            icon = {
                Icon(
                    Icons.Default.Devices,
                    contentDescription = stringResource(R.string.serverlist_add_gateway_btn),
                )
            },
            text = {
                Text(
                    if (hasGateways) {
                        stringResource(R.string.serverlist_add_paired_btn)
                    } else {
                        stringResource(R.string.serverlist_add_gateway_btn)
                    },
                )
            },
        )
        FloatingActionButtonMenuItem(
            onClick = onNavigateToAddServer,
            modifier = Modifier.handCursor(),
            icon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.serverlist_add_agent_btn),
                )
            },
            text = { Text(stringResource(R.string.serverlist_add_agent_btn)) },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddFabToggle(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    ToggleFloatingActionButton(
        checked = expanded,
        onCheckedChange = onExpandedChange,
        modifier = Modifier.handCursor(),
    ) {
        val fabRotation by animateFloatAsState(
            targetValue = if (expanded) 45f else 0f,
            // Spatial motion: a touch of spring overshoot as the icon turns.
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "fabRotation",
        )
        val fabTint by animateColorAsState(
            targetValue =
                if (expanded) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    // The unchecked container is primaryContainer, whose guaranteed pair is onPrimaryContainer.
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            // Color is a non-spatial effect: spring without bounce.
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "fabTint",
        )
        Icon(
            modifier = Modifier.graphicsLayer { rotationZ = fabRotation },
            imageVector = Icons.Default.Add,
            tint = fabTint,
            contentDescription =
                if (expanded) {
                    stringResource(R.string.serverlist_fab_close)
                } else {
                    stringResource(R.string.serverlist_fab_add)
                },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyServerList(
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Lowest tone, like the agents sheet it stands in for: the scaffold behind is Low.
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth(),
    ) {
        // In the empty state, adding an agent IS the hero action.
        ErrorStateCard(
            headline = stringResource(R.string.serverlist_empty_title),
            body = stringResource(R.string.serverlist_empty_body),
            icon = Icons.Default.SmartToy,
            medallionContainer = MaterialTheme.colorScheme.primaryContainer,
            medallionContent = MaterialTheme.colorScheme.onPrimaryContainer,
            medallionShape = MaterialShapes.Cookie9Sided.toShape(),
            ctaLabel = stringResource(R.string.serverlist_empty_action),
            onCta = onAddServer,
            ctaIsHero = true,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
        )
    }
}
