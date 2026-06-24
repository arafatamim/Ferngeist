package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.RecentSession
import com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel

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
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val hasGateways by viewModel.hasGateways.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddMenu by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
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

    BackHandler(showAddMenu) {
        showAddMenu = false
    }

    pendingAuthentication?.let {
        PendingAuthenticationDialog(
            pendingAuthentication = it,
            selectedAuthMethodId = selectedAuthMethodId,
            onSelectedAuthMethodChange = { methodId -> selectedAuthMethodId = methodId },
            envValues = envValues,
            onSubmit = { methodId, values -> viewModel.authenticate(it.serverId, methodId, values) },
            onReconnect = { viewModel.retryPendingAuthentication(it.serverId) },
            onDismiss = viewModel::dismissAuthenticationPrompt,
        )
    }

    pendingLaunchConsent?.let { pending ->
        LaunchRiskConsentDialog(
            pending = pending,
            onConfirm = { viewModel.confirmLaunchConsent(pending.serverId) },
            onDismiss = viewModel::dismissLaunchConsent,
        )
    }



    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Background surface; the lighter foreground "Your agents" sheet reads as elevated against it.
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            ServerListTopBar(
                scrollBehavior = scrollBehavior,
                onAboutClick = { showAboutDialog = true },
            )
        },
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = showAddMenu,
                button = {
                    ToggleFloatingActionButton(
                        checked = showAddMenu,
                        onCheckedChange = { showAddMenu = it },
                    ) {
                        val fabRotation by animateFloatAsState(
                            targetValue = if (showAddMenu) 45f else 0f,
                            // Spatial motion: a touch of spring overshoot as the icon turns.
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            label = "fabRotation",
                        )
                        val fabTint by animateColorAsState(
                            targetValue = if (showAddMenu) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                            // Color is a non-spatial effect: spring without bounce.
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            label = "fabTint",
                        )
                        Icon(
                            modifier = Modifier.graphicsLayer { rotationZ = fabRotation },
                            imageVector = Icons.Default.Add,
                            tint = fabTint,
                            contentDescription = if (showAddMenu) stringResource(R.string.serverlist_fab_close) else stringResource(R.string.serverlist_fab_add),
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        showAddMenu = false
                        if (hasGateways) {
                            onNavigateToGateways()
                        } else {
                            onNavigateToPairGateway()
                        }
                    },
                    icon = { Icon(Icons.Default.Devices, contentDescription = stringResource(R.string.serverlist_add_gateway_btn)) },
                    text = { Text(if (hasGateways) stringResource(R.string.serverlist_add_paired_btn) else stringResource(R.string.serverlist_add_gateway_btn)) },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        showAddMenu = false
                        onNavigateToAddServer()
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.serverlist_add_agent_btn)) },
                    text = { Text(stringResource(R.string.serverlist_add_agent_btn)) },
                )
            }
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

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when {
                isLoading && servers.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Default expressive indicator morphs through a shape sequence.
                        LoadingIndicator(modifier = Modifier.size(48.dp))
                    }
                }
                servers.isEmpty() -> {
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
                            onAddServer = onNavigateToAddServer,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(modifier = Modifier.height(96.dp))
                    }
                }
                else -> {
                    AgentsBackdrop(
                        heroSession = heroSession,
                        olderSessions = olderSessions,
                        servers = servers,
                        uiState = uiState,
                        onResumeSession = onResumeSession,
                        onConnect = { viewModel.connectAndOpenServer(it) },
                        onEdit = onNavigateToEditServer,
                        onDelete = { viewModel.deleteServer(it) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                    )
                }
            }
        }
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
