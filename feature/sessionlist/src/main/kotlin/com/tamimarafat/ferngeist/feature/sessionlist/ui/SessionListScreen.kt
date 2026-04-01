package com.tamimarafat.ferngeist.feature.sessionlist.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.common.ui.SessionSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.SessionTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.connectionStateLabel
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.max
import androidx.compose.ui.platform.LocalLocale

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun SessionListScreen(
    serverName: String,
    openCreateSessionDialogOnLaunch: Boolean = false,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    viewModel: SessionListViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val sessions by viewModel.sessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val server by viewModel.server.collectAsState()
    val sessionSettings by viewModel.sessionSettings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val agentCapabilities by viewModel.agentCapabilities.collectAsState()
    val connectionDiagnostics by viewModel.connectionDiagnostics.collectAsState()
    val pendingAuthentication by viewModel.pendingAuthentication.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentCwd = sessionSettings.cwd
    var showCwdDialog by remember { mutableStateOf(false) }
    var cwdDialogValue by remember(currentCwd) { mutableStateOf(currentCwd.orEmpty()) }
    var showConnectionStatusDialog by remember { mutableStateOf(false) }
    var hasConsumedLaunchCreate by rememberSaveable { mutableStateOf(false) }
    var selectedAuthMethodId by rememberSaveable(
        pendingAuthentication?.serverId,
        pendingAuthentication?.pendingAction
    ) {
        mutableStateOf(
            pendingAuthentication?.preferredAuthMethodId ?: pendingAuthentication?.authMethods?.firstOrNull()?.id
        )
    }
    val envValues = remember(
        pendingAuthentication?.serverId,
        pendingAuthentication?.pendingAction
    ) { mutableStateMapOf<String, String>() }
    val pullToRefreshState = rememberPullToRefreshState()
    val showRefreshingIndicator = isLoading && sessions.isNotEmpty()
    val supportsSessionList = agentCapabilities?.session?.list != false

    LaunchedEffect(
        pendingAuthentication?.serverId,
        pendingAuthentication?.pendingAction,
        pendingAuthentication?.persistedEnvValues
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCwdDialog = false },
            title = { Text("Working Directory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Set the current working directory for this agent. Leave empty to show all sessions.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = cwdDialogValue,
                        onValueChange = { cwdDialogValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Leave empty for no filter") }
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showCwdDialog = false
                            viewModel.updateCurrentCwd(cwdDialogValue)
                        }
                    ) { Text("Save") }
                    if (currentCwd != null) {
                        TextButton(
                            onClick = {
                                showCwdDialog = false
                                viewModel.updateCurrentCwd("")
                            }
                        ) { Text("Clear") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCwdDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showConnectionStatusDialog) {
        ConnectionDiagnosticsDialog(
            connectionState = connectionState,
            diagnostics = connectionDiagnostics,
            showCancelSupport = true,
            onDismiss = { showConnectionStatusDialog = false }
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
    val titleStyle = lerp(
        MaterialTheme.typography.headlineMedium, // expanded
        MaterialTheme.typography.titleLarge,    // collapsed
        collapse
    )

    val containerModifier = if (supportsSessionList) {
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .pullToRefresh(
                state = pullToRefreshState,
                isRefreshing = showRefreshingIndicator,
                onRefresh = viewModel::refreshSessions,
            )
    } else {
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    }

    Box(
        modifier = containerModifier
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text(
                                text = serverName,
                                style = titleStyle
                            )
                            currentCwd?.let { cwd ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = cwd,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        FilledTonalIconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                TooltipAnchorPosition.Above
                            ),
                            tooltip = { PlainTooltip { Text("Change working directory") } },
                            state = rememberTooltipState()
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    cwdDialogValue = currentCwd.orEmpty()
                                    showCwdDialog = true
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = "Change working directory",
                                )
                            }
                        }
                        val connectionLabel = connectionStateLabel(connectionState)
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                TooltipAnchorPosition.Above
                            ),
                            tooltip = { PlainTooltip { Text("Connection: $connectionLabel") } },
                            state = rememberTooltipState()
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = "Connection status"
                                        stateDescription = connectionLabel
                                    }
                                    .clickable(onClick = { showConnectionStatusDialog = true }),
                                contentAlignment = Alignment.Center
                            ) {
                                ConnectionStatusDot(connectionState = connectionState)
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        viewModel.createSessionWithCurrentCwd()
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New session"
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            when {
                isLoading && sessions.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }

                sessions.isEmpty() -> {
                    EmptySessionList(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        supportsSessionList = supportsSessionList,
                        onCreateSession = { viewModel.createSessionWithCurrentCwd() }
                    )
                }

                else -> {
                    val zoneId = ZoneId.systemDefault()
                    val today = LocalDate.now(zoneId)
                    val sortedSessions = sessions.sortedWith(
                        compareByDescending<SessionSummary> { it.updatedAt ?: Long.MIN_VALUE }
                            .thenByDescending { it.id }
                    )
                    val groupedSessions = linkedMapOf<String, List<SessionSummary>>()
                    val withDate =
                        sortedSessions.filter { it.updatedAt != null }.groupBy { session ->
                            val updatedAt = session.updatedAt ?: 0L
                            Instant.ofEpochMilli(updatedAt).atZone(zoneId).toLocalDate()
                        }
                    withDate.entries
                        .sortedByDescending { it.key }
                        .forEach { (sessionDate, groupSessions) ->
                            val label = when (sessionDate) {
                                today -> "Today"
                                today.minusDays(1) -> "Yesterday"
                                else -> {
                                    val epoch = max(
                                        groupSessions.firstOrNull()?.updatedAt ?: 0L,
                                        0L
                                    )
                                    SimpleDateFormat("MMMM d, yyyy", LocalLocale.current.platformLocale)
                                        .format(Date(epoch))
                                }
                            }
                            groupedSessions[label] = groupSessions
                        }
                    val unknownSessions = sortedSessions.filter { it.updatedAt == null }
                    if (unknownSessions.isNotEmpty()) {
                        groupedSessions["Unknown"] = unknownSessions
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
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
                                text = "${sessions.size} session${if (sessions.size != 1) "s" else ""}",
                                modifier = Modifier
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
                isRefreshing = showRefreshingIndicator,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
        }
    }
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
    val selectedMethod = pendingAuthentication.authMethods.firstOrNull { it.id == selectedAuthMethodId }
        ?: pendingAuthentication.authMethods.firstOrNull()
    val isHelperEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.helperRuntimeId != null
    val isManualEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.helperRuntimeId == null
    val requiredEnvVarsFilled = selectedMethod
        ?.envVars
        ?.all { envVar -> envVar.optional || !envValues[envVar.name].isNullOrBlank() }
        ?: false

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Authenticate ${pendingAuthentication.serverName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "The agent \"${pendingAuthentication.agentName}\" requires ACP authentication before sessions can be opened.",
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(
                                selected = selectedMethod?.id == method.id,
                                onClick = { onSelectedAuthMethodChange(method.id) },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(text = method.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = method.description ?: "Type: ${method.type}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (selectedMethod?.id == method.id) {
                                    AuthenticationMethodDetails(
                                        method = method,
                                        envValues = envValues,
                                        isHelperBacked = pendingAuthentication.helperRuntimeId != null,
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
                enabled = when {
                    selectedMethod == null -> false
                    isHelperEnvAuth -> requiredEnvVarsFilled
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
                Text(if (isManualEnvAuth) "Reconnect" else "Authenticate")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AuthenticationMethodDetails(
    method: AcpAuthMethodInfo,
    envValues: MutableMap<String, String>,
    isHelperBacked: Boolean,
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
            text = "Command: ${method.args.joinToString(" ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (method.type != "env") {
        return
    }
    if (!isHelperBacked) {
        Text(
            text = "Set these environment variables before launching the agent, then reconnect to retry authentication.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        method.envVars.forEach { envVar ->
            Text(
                text = buildString {
                    append(envVar.label ?: envVar.name)
                    append(" -> ")
                    append(envVar.name)
                    if (envVar.optional) {
                        append(" (optional)")
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
                            append(" (optional)")
                        }
                    }
                )
            },
            supportingText = { Text(envVar.name) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (envVar.secret) KeyboardType.Password else KeyboardType.Text,
            ),
            visualTransformation = if (envVar.secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        )
    }
}

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
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(
                        key = SessionSharedBoundsKey(session.id),
                    ),
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                )
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title ?: "Untitled Session",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(
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
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

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
                text = "No sessions",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (supportsSessionList) {
                    "Sessions from the server will appear here"
                } else {
                    "Sessions you create on this device will appear here"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(240.dp),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onCreateSession) {
                Text("Create new session")
            }
        }
    }
}

@Composable
private fun ConnectionStatusDot(connectionState: AcpConnectionState) {
    when (connectionState) {
        is AcpConnectionState.Connecting -> {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        }

        is AcpConnectionState.Connected -> {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .padding(1.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32))
                ) {}
            }
        }

        is AcpConnectionState.Failed -> {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .padding(1.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error)
                ) {}
            }
        }

        is AcpConnectionState.Disconnected -> {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .padding(1.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.outlineVariant)
                ) {}
            }
        }
    }
}
