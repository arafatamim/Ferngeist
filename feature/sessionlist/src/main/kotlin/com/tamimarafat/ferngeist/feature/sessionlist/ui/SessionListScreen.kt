package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.annotations.UnstableApi
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.common.ui.ConnectionStatusPill
import com.tamimarafat.ferngeist.core.common.ui.ServerNameSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.SessionSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.SessionTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.R
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
    ExperimentalSharedTransitionApi::class, UnstableApi::class,
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
    val sessions by viewModel.sessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val sessionSettings by viewModel.sessionSettings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val agentCapabilities by viewModel.agentCapabilities.collectAsState()
    val connectionDiagnostics by viewModel.connectionDiagnostics.collectAsState()
    val pendingAuthentication by viewModel.pendingAuthentication.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val recentCwds by viewModel.recentCwds.collectAsState()
    val currentCwd = sessionSettings.cwd
    var showCwdDialog by remember { mutableStateOf(false) }
    var cwdDialogValue by remember(currentCwd) { mutableStateOf(currentCwd.orEmpty()) }
    var showConnectionStatusDialog by remember { mutableStateOf(false) }
    var hasConsumedLaunchCreate by rememberSaveable { mutableStateOf(false) }
    var selectedAuthMethodId by rememberSaveable(
        pendingAuthentication?.serverId,
        pendingAuthentication?.pendingAction,
    ) {
        mutableStateOf(
            pendingAuthentication?.preferredAuthMethodId ?: pendingAuthentication?.authMethods?.firstOrNull()?.id,
        )
    }
    val envValues =
        remember(
            pendingAuthentication?.serverId,
            pendingAuthentication?.pendingAction,
        ) { mutableStateMapOf<String, String>() }
    // Separate flag: PullToRefreshDefaults.LoadingIndicator only shows on user-pull,
    // not on initial load when cached sessions exist (isLoading alone would trigger it).
    val isRefreshing by viewModel.refreshing.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()
    val supportsSessionList = agentCapabilities?.sessionCapabilities?.list != null
    val serverName = resolveServerDisplayName(
        navArgName,
        loadedName,
        stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_topbar_title),
    )
    val hasCwd = !currentCwd.isNullOrBlank()
    val cwdAlpha by animateFloatAsState(
        targetValue = if (hasCwd) 1f else 0f,
        label = "cwdAlpha",
    )

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
        selectedAuthMethodId = pendingAuthentication?.preferredAuthMethodId
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
        if (openCreateSessionDialogOnLaunch && !hasConsumedLaunchCreate) {
            hasConsumedLaunchCreate = true
            viewModel.createSessionWithCurrentCwd()
        }
    }

    if (showCwdDialog) {
        CwdDialog(
            recentCwds = recentCwds,
            cwdDialogValue = cwdDialogValue,
            onCwdDialogValueChange = { cwdDialogValue = it },
            onSave = {
                showCwdDialog = false
                viewModel.updateCurrentCwd(cwdDialogValue)
            },
            onClear = if (currentCwd != null) {
                {
                    showCwdDialog = false
                    viewModel.updateCurrentCwd("")
                }
            } else null,
            onDismiss = { showCwdDialog = false },
            onRemoveRecentCwd = viewModel::removeRecentCwd,
        )
    }

    if (showConnectionStatusDialog) {
        ConnectionDiagnosticsDialog(
            connectionState = connectionState,
            diagnostics = connectionDiagnostics,
            onDismiss = { showConnectionStatusDialog = false },
        )
    }

    pendingAuthentication?.let { pending ->
        PendingAuthenticationDialog(
            pendingAuthentication = pending,
            selectedAuthMethodId = selectedAuthMethodId,
            onSelectedAuthMethodChange = { selectedAuthMethodId = it },
            envValues = envValues,
            onSubmit = { methodId, values -> viewModel.authenticate(methodId, values) },
            onReconnect = viewModel::reconnectPendingAuthentication,
            onDismiss = viewModel::dismissAuthenticationPrompt,
        )
    }

    val collapse = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
    // Interpolate title size between expanded (headlineMedium) and collapsed (titleLarge)
    // as the user scrolls — gives a smooth visual transition in the top bar.
    val titleStyle =
        lerp(
            MaterialTheme.typography.headlineMedium, // expanded
            MaterialTheme.typography.titleLarge, // collapsed
            collapse,
        )

    val containerModifier =
        if (supportsSessionList) {
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .pullToRefresh(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshSessions(isUserInitiated = true) },
                )
        } else {
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        }

    Box(
        modifier = containerModifier,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TwoRowsTopAppBar(
                    title = { expanded ->
                        SessionListTopBarTitle(
                            expanded = expanded,
                            collapsedFraction = collapse,
                            serverId = serverId,
                            serverName = serverName,
                            titleStyle = titleStyle,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope,
                        )
                    },
                    subtitle = { expanded ->
                        if (expanded) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
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
                    },
                    navigationIcon = {
                        FilledTonalIconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_back_desc),
                            )
                        }
                    },
                    actions = {
                        TooltipBox(
                            positionProvider =
                                TooltipDefaults.rememberTooltipPositionProvider(
                                    TooltipAnchorPosition.Above,
                                ),
                            tooltip = { PlainTooltip { Text(stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_cwd_tooltip)) } },
                            state = rememberTooltipState(),
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    cwdDialogValue = currentCwd.orEmpty()
                                    showCwdDialog = true
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_cwd_desc),
                                )
                            }
                        }
                        ConnectionStatusPill(
                            connectionState = connectionState,
                            onClick = { showConnectionStatusDialog = true },
                        )
                    },
                    collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
                    expandedHeight = TopAppBarDefaults.LargeAppBarExpandedHeight,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        viewModel.createSessionWithCurrentCwd()
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_new_session_desc),
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            when {
                isLoading && sessions.isEmpty() -> {
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

                sessions.isEmpty() -> {
                    EmptySessionList(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                        supportsSessionList = supportsSessionList,
                        onCreateSession = { viewModel.createSessionWithCurrentCwd() },
                    )
                }

                else -> {
                    val zoneId = ZoneId.systemDefault()
                    val today = LocalDate.now(zoneId)
                    val locale = LocalLocale.current
                    val dateFormatter = remember(locale) {
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
                                    today -> stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_today)
                                    today.minusDays(1) -> stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_yesterday)
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
                        groupedSessions[stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_unknown_date)] =
                            unknownSessions
                    }

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
                                    onClick = {
                                        onNavigateToChat(
                                            session.id,
                                            session.cwd ?: "/",
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
                                text = LocalResources.current.getQuantityString(
                                    com.tamimarafat.ferngeist.feature.sessionlist.R.plurals.sessionlist_session_count,
                                    sessions.size,
                                    sessions.size,
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
            }
        }

        if (supportsSessionList) {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
            )
        }
    }
}

/**
 * Dialog for ACP authentication with method selection, env var input, and reconnect.
 *
 * Supports three auth flows:
 * 1. **Gateway env auth**: user fills env var fields inline → `onSubmit`
 * 2. **Manual env auth**: user sets env vars manually → "Reconnect" → `onReconnect`
 * 3. **Other methods** (token, etc.): `onSubmit` immediately
 */
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
                    com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_title,
                    pendingAuthentication.serverName,
                ),
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_body,
                        pendingAuthentication.agentName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                pendingAuthentication.authErrorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                pendingAuthentication.authMethods.forEach { method ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(
                                selected = selectedMethod?.id == method.id,
                                onClick = { onSelectedAuthMethodChange(method.id) },
                            )
                            Column(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .padding(top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(text = method.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = method.description ?: stringResource(
                                        com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_method_fallback,
                                        method.type,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (selectedMethod?.id == method.id) {
                                    AuthenticationMethodDetails(
                                        method = method,
                                        envValues = envValues,
                                        isGatewayBacked = pendingAuthentication.gatewayRuntimeId != null,
                                        onOpenLink = { uriHandler.openUri(it) },
                                        onEnvValueChange = { name, value -> envValues[name] = value },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // Enabled: method selected; for gateway env auth all required vars must be filled.
                enabled =
                    when {
                        selectedMethod == null -> false
                        isGatewayEnvAuth -> requiredEnvVarsFilled
                        else -> true
                    },
                onClick = {
                    when {
                        selectedMethod == null -> Unit
                        isManualEnvAuth -> onReconnect()
                        else -> onSubmit(selectedMethod.id, envValues)
                    }
                },
            ) {
                Text(
                    if (isManualEnvAuth) stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_reconnect) else stringResource(
                        com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_authenticate,
                    ),
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_cancel))
            }
        },
    )
}

/**
 * Renders auth method-specific details below the selected method.
 *
 * Three branches:
 * 1. Method has a link → `TextButton` to open it
 * 2. Method is manual-env ("env" but not gateway-backed) → lists required env vars with instructions
 * 3. Method is gateway-env ("env" + gateway-backed) → inline `OutlinedTextField` per env var
 */
@Composable
private fun AuthenticationMethodDetails(
    method: AcpAuthMethodInfo,
    envValues: MutableMap<String, String>,
    isGatewayBacked: Boolean,
    onOpenLink: (String) -> Unit,
    onEnvValueChange: (String, String) -> Unit,
) {
    method.link?.let { link ->
        TextButton(onClick = { onOpenLink(link) }) {
            Text(link)
        }
    }
    if (method.args.isNotEmpty()) {
        Text(
            text = stringResource(
                com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_terminal_cmd,
                method.args.joinToString(" "),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (method.type != "env") {
        return
    }
    if (!isGatewayBacked) {
        Text(
            text = stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_env_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        method.envVars.forEach { envVar ->
            Text(
                text =
                    buildString {
                        append(envVar.label ?: envVar.name)
                        append(" -> ")
                        append(envVar.name)
                        if (envVar.optional) {
                            append(stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_optional_suffix))
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    method.envVars.forEach { envVar ->
        OutlinedTextField(
            value = envValues[envVar.name].orEmpty(),
            onValueChange = { onEnvValueChange(envVar.name, it) },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    buildString {
                        append(envVar.label ?: envVar.name)
                        if (envVar.optional) {
                            append(stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_auth_optional_suffix))
                        }
                    },
                )
            },
            supportingText = { Text(envVar.name) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (envVar.secret) KeyboardType.Password else KeyboardType.Text,
                ),
            visualTransformation =
                if (envVar.secret) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
        )
    }
}

/**
 * Tappable card for a single session in the list.
 *
 * Uses [sharedBounds] for a shared-element transition to the chat screen,
 * keyed by [SessionSharedBoundsKey] (outer card) and [SessionTitleSharedBoundsKey] (title text).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SessionCard(
    session: SessionSummary,
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
                    )
                    .fillMaxWidth()
                    .clip(CardDefaults.shape)
                    .clickable(onClick = onClick),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title
                            ?: stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_untitled),
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
    }
}

/**
 * Shown when there are no sessions.
 * Text adapts based on whether the agent supports session listing.
 */
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_empty_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    if (supportsSessionList) {
                        stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_empty_subtitle_listed)
                    } else {
                        stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_empty_subtitle_offline)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(240.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onCreateSession) {
                Text(stringResource(com.tamimarafat.ferngeist.feature.sessionlist.R.string.sessionlist_empty_action))
            }
        }
    }
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
    val sharedContentState = with(sharedTransitionScope) {
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
