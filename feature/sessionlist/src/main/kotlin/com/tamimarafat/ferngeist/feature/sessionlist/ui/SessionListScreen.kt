package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.RpcDirection
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.text.NumberFormat
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    serverId: String,
    serverName: String,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    viewModel: SessionListViewModel,
) {
    val sessions by viewModel.sessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val server by viewModel.server.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectionDiagnostics by viewModel.connectionDiagnostics.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val defaultCwd = server?.workingDirectory?.takeIf { it.isNotBlank() } ?: "/"
    var showCreateSessionDialog by remember { mutableStateOf(false) }
    var createSessionCwd by remember(defaultCwd) { mutableStateOf(defaultCwd) }
    var showConnectionStatusDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionListEvent.NavigateToChat -> {
                    onNavigateToChat(event.sessionId, event.cwd, event.updatedAt, event.title)
                }
                is SessionListEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    if (showCreateSessionDialog) {
        AlertDialog(
            onDismissRequest = { showCreateSessionDialog = false },
            title = { Text("New Session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Working directory (cwd)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = createSessionCwd,
                        onValueChange = { createSessionCwd = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("/") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreateSessionDialog = false
                        viewModel.createSession(createSessionCwd)
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateSessionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showConnectionStatusDialog) {
        ConnectionStatusDialog(
            connectionState = connectionState,
            diagnostics = connectionDiagnostics,
            onDismiss = { showConnectionStatusDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = "${sessions.size} session${if (sessions.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
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
                    IconButton(
                        onClick = { viewModel.refreshSessions() },
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh sessions"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    createSessionCwd = defaultCwd
                    showCreateSessionDialog = true
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading && sessions.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                isLoading && sessions.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                sessions.isEmpty() -> {
                    EmptySessionList(
                        modifier = Modifier.fillMaxSize(),
                        onCreateSession = {
                            createSessionCwd = defaultCwd
                            showCreateSessionDialog = true
                        }
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
                    val withDate = sortedSessions.filter { it.updatedAt != null }.groupBy { session ->
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
                                    SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
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
                        contentPadding = PaddingValues(16.dp),
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
                                    onClick = { viewModel.onSessionClick(session) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
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

@Composable
private fun EmptySessionList(
    modifier: Modifier = Modifier,
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
                text = "Sessions from the server will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ConnectionStatusDialog(
    connectionState: AcpConnectionState,
    diagnostics: ConnectionDiagnostics,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val totalTokensText = diagnostics.lastTotalTokens?.let {
        formatCompactTokens(it, Locale.getDefault())
    } ?: "N/A"
    val contextUsagePct = percentString(
        diagnostics.lastTotalTokens,
        diagnostics.lastContextWindowTokens,
        Locale.getDefault()
    ) ?: "N/A"
    val costText = diagnostics.lastCostAmount?.let { amount ->
        formatCurrency(amount, diagnostics.lastCostCurrency, Locale.getDefault())
    } ?: "Unavailable"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Diagnostics") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Connection: ${connectionStateLabel(connectionState)}")
                Text("WebSocket: ${diagnostics.websocketState.name.lowercase().replace('_', ' ')}")
                Text("Server: ${diagnostics.serverUrl ?: "Unknown"}")
                val agentName = diagnostics.agentInfo?.name ?: "Unknown"
                val agentVersion = diagnostics.agentInfo?.version ?: "Unknown"
                Text("Server info: $agentName ($agentVersion)")
                Text("Pending RPC requests: ${diagnostics.pendingRequestCount}")
                Text(
                    "Cancel support: ${when (diagnostics.supportsSessionCancel) {
                        true -> "Supported"
                        false -> "Unsupported"
                        null -> "Unknown"
                    }}"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Total tokens used: $totalTokensText")
                Text("Usage percentage: $contextUsagePct")
                Text("Cost spent: $costText")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Recent RPC Activity", style = MaterialTheme.typography.titleSmall)
                if (diagnostics.recentRpc.isEmpty()) {
                    Text(
                        "No recent RPC activity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    diagnostics.recentRpc.takeLast(12).reversed().forEach { entry ->
                        val rpcIdText = entry.rpcId?.let { " #$it" } ?: ""
                        val summaryText = entry.summary?.let { " - $it" } ?: ""
                        Text(
                            text = "${formatDiagnosticsTime(entry.timestampMs)}  ${directionLabel(entry.direction)} ${entry.method}$rpcIdText$summaryText",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Recent Errors", style = MaterialTheme.typography.titleSmall)
                if (diagnostics.recentErrors.isEmpty()) {
                    Text(
                        "No recent errors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    diagnostics.recentErrors.takeLast(8).reversed().forEach { entry ->
                        Text(
                            text = "${formatDiagnosticsTime(entry.timestampMs)}  ${entry.source}: ${entry.message}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun connectionStateLabel(state: AcpConnectionState): String = when (state) {
    is AcpConnectionState.Connecting -> "Connecting"
    is AcpConnectionState.Connected -> "Connected"
    is AcpConnectionState.Failed -> "Failed"
    is AcpConnectionState.Disconnected -> "Disconnected"
}

private fun directionLabel(direction: RpcDirection): String = when (direction) {
    RpcDirection.OutboundRequest -> "REQ"
    RpcDirection.InboundResult -> "RES"
    RpcDirection.InboundError -> "ERR"
    RpcDirection.InboundNotification -> "NTF"
}

private fun formatDiagnosticsTime(timestampMs: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}

private fun percentString(part: Int?, total: Int?, locale: Locale): String? {
    if (part == null || total == null || total <= 0) return null
    val percent = part.toDouble() / total.toDouble()
    return NumberFormat.getPercentInstance(locale).apply {
        maximumFractionDigits = 0
    }.format(percent)
}

private fun formatCurrency(amount: Double, currencyCode: String?, locale: Locale): String {
    return NumberFormat.getCurrencyInstance(locale).apply {
        currencyCode?.let {
            runCatching { currency = java.util.Currency.getInstance(it) }
        }
        maximumFractionDigits = 2
    }.format(amount)
}

private fun formatCompactTokens(tokens: Int, locale: Locale): String {
    val absolute = kotlin.math.abs(tokens.toLong())
    return when {
        absolute >= 1_000_000_000L -> {
            val value = kotlin.math.round(tokens / 1_000_000_000.0).toInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}B"
        }
        absolute >= 1_000_000L -> {
            val value = kotlin.math.round(tokens / 1_000_000.0).toInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}M"
        }
        absolute >= 1_000L -> {
            val value = kotlin.math.round(tokens / 1_000.0).toInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}k"
        }
        else -> NumberFormat.getIntegerInstance(locale).format(tokens)
    }
}
