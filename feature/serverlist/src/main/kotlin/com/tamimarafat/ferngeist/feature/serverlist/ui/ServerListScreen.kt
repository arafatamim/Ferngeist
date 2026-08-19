package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.serverlist.PendingAuthentication
import com.tamimarafat.ferngeist.feature.serverlist.PendingLaunchConsent
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.RecentSession
import com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel

private data class ServerListScreenState(
    val servers: List<LaunchableTarget>,
    val hasGateways: Boolean,
    val uiState: ServerListUiState,
    val isLoading: Boolean,
    val recentSessions: List<RecentSession>,
    val snackbarHostState: SnackbarHostState,
    val pendingAuthentication: PendingAuthentication?,
    val pendingLaunchConsent: PendingLaunchConsent?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberServerListState(viewModel: ServerListViewModel): ServerListScreenState {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val hasGateways by viewModel.hasGateways.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    return ServerListScreenState(
        servers = servers,
        hasGateways = hasGateways,
        uiState = uiState,
        isLoading = isLoading,
        recentSessions = recentSessions,
        snackbarHostState = snackbarHostState,
        pendingAuthentication = uiState.pendingAuthentication,
        pendingLaunchConsent = uiState.pendingLaunchConsent,
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
    viewModel: ServerListViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
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

    ServerListDialogsAndEffects(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        showAboutDialog = showAboutDialog,
        onDismissAbout = { showAboutDialog = false },
        onNavigateToSessions = onNavigateToSessions,
    )

    BackHandler(showAddMenu) {
        showAddMenu = false
    }

    ServerListScaffold(
        snackbarHostState = snackbarHostState,
        showAddMenu = showAddMenu,
        onShowAddMenuChange = { showAddMenu = it },
        hasGateways = hasGateways,
        onAboutClick = { showAboutDialog = true },
        onNavigateToGateways = onNavigateToGateways,
        onNavigateToPairGateway = onNavigateToPairGateway,
        onNavigateToAddServer = onNavigateToAddServer,
        isLoading = isLoading,
        servers = servers,
        recentSessions = recentSessions,
        uiState = uiState,
        onResumeSession = onResumeSession,
        onConnect = { viewModel.connectAndOpenServer(it) },
        onEdit = onNavigateToEditServer,
        onDelete = { viewModel.deleteServer(it) },
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
private fun ServerListScaffold(
    snackbarHostState: SnackbarHostState,
    showAddMenu: Boolean,
    onShowAddMenuChange: (Boolean) -> Unit,
    onAboutClick: () -> Unit,
    hasGateways: Boolean,
    onNavigateToGateways: () -> Unit,
    onNavigateToPairGateway: () -> Unit,
    onNavigateToAddServer: () -> Unit,
    isLoading: Boolean,
    servers: List<LaunchableTarget>,
    recentSessions: List<RecentSession>,
    uiState: ServerListUiState,
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
                expanded = showAddMenu,
                onExpandedChange = onShowAddMenuChange,
                hasGateways = hasGateways,
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
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.medium,
                )
            }
        },
    ) { padding ->
        val heroSession = recentSessions.firstOrNull()
        val olderSessions = if (recentSessions.size > 1) recentSessions.drop(1) else emptyList()

        ServerListContent(
            modifier = Modifier.padding(padding),
            isLoading = isLoading,
            servers = servers,
            heroSession = heroSession,
            olderSessions = olderSessions,
            uiState = uiState,
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
    uiState: ServerListUiState,
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
            isLoading && servers.isEmpty() -> ServerListLoadingContent()
            servers.isEmpty() ->
                EmptyServerContent(
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
                    uiState = uiState,
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
                onClick = { onResumeSession(hero) },
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        olderSessions.forEach { session ->
            RecentSessionCard(
                session = session,
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
    onNavigateToGateways: () -> Unit,
    onNavigateToAddServer: () -> Unit,
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
            onClick = onNavigateToGateways,
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
                    MaterialTheme.colorScheme.primary
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
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(76.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(38.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.serverlist_empty_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.serverlist_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // In the empty state, adding an agent IS the hero action: a filled button
            // whose corner morphs from round to square on press.
            Button(
                onClick = onAddServer,
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.serverlist_empty_action))
            }
        }
    }
}
