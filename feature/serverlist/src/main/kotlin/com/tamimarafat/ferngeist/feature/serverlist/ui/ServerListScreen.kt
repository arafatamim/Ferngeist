package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.serverlist.ServerListEvent
import com.tamimarafat.ferngeist.feature.serverlist.PendingAuthentication
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.PendingLaunchConsent

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServerListScreen(
    onNavigateToAddServer: () -> Unit,
    onNavigateToPairGateway: () -> Unit,
    onNavigateToGateways: () -> Unit,
    onNavigateToEditServer: (LaunchableTarget) -> Unit,
    onNavigateToSessions: (String, List<SessionSummary>, Boolean) -> Unit,
    viewModel: ServerListViewModel,
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val hasGateways by viewModel.hasGateways.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddMenu by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    val pendingAuthentication = uiState.pendingAuthentication
    val pendingLaunchConsent = uiState.pendingLaunchConsent
    var selectedAuthMethodId by rememberSaveable(pendingAuthentication?.serverId) {
        mutableStateOf(uiState.pendingAuthentication?.authMethods?.firstOrNull()?.id)
    }
    val envValues = remember(pendingAuthentication?.serverId) { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ServerListEvent.NavigateToSessions -> onNavigateToSessions(
                    event.serverId,
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
                        Icon(
                            imageVector = if (showAddMenu) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = null,
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
                    icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                    text = { Text(if (hasGateways) "Add using paired gateway" else "Pair Ferngeist Gateway") },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        showAddMenu = false
                        onNavigateToAddServer()
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add agent manually") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (servers.isEmpty()) {
                item(key = "empty") {
                    EmptyServerList(onAddServer = onNavigateToAddServer)
                }
            } else {
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        uiState = uiState,
                        onClick = { viewModel.connectAndOpenServer(server) },
                        onEdit = { onNavigateToEditServer(server) },
                        onDelete = { viewModel.deleteServer(server.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchRiskConsentDialog(
    pending: PendingLaunchConsent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var acknowledgedRisk by rememberSaveable(pending.serverId) { mutableStateOf(false) }
    val riskLines = launchRiskLines(
        serverName = pending.serverName,
        agentId = pending.agentId,
        gatewayHost = pending.gatewayHost,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run agent on gateway?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                riskLines.forEach { line ->
                    Text(
                        text = "- $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = acknowledgedRisk,
                        onCheckedChange = { acknowledgedRisk = it },
                    )
                    Text(
                        text = "I understand and want to continue.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = acknowledgedRisk,
                onClick = onConfirm,
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerListTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onAboutClick: () -> Unit,
) {
    val collapse = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
    val titleStyle = lerp(
        MaterialTheme.typography.displayMedium,
        MaterialTheme.typography.titleLarge,
        collapse
    )

    LargeTopAppBar(
        title = { Text(text = "Ferngeist", style = titleStyle) },
        actions = {
            AnimatedVisibility(
                visible = collapse > 0.5f,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                IconButton(onClick = onAboutClick) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.about)
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val privacyPolicyUrl = stringResource(R.string.privacy_policy_url)
    val githubRepoUrl = stringResource(R.string.github_repo_url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Ferngeist is an Android client for Agent Client Protocol (ACP) servers, allowing you to interact with coding agents directly from your mobile device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = { uriHandler.openUri(privacyPolicyUrl) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.privacy_policy))
                    }
                    TextButton(
                        onClick = { uriHandler.openUri(githubRepoUrl) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.report_issue))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}

@Composable
private fun PendingAuthenticationDialog(
    pendingAuthentication: PendingAuthentication,
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
    val isGatewayEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.GatewayRuntime != null
    val isManualEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.GatewayRuntime == null
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
                                        isGatewayBacked = pendingAuthentication.GatewayRuntime != null,
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
            text = "Command: ${method.args.joinToString(" ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (method.type != "env") {
        return
    }
    if (!isGatewayBacked) {
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

@Composable
private fun EmptyServerList(
    onAddServer: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "No agents yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Add an agent manually or pair a gateway to build your launch list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onAddServer) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add agent")
            }
        }
    }
}
