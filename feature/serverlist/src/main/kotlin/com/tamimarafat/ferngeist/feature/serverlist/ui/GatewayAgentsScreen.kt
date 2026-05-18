package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.serverlist.GatewayAgentsViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayAgent

/**
 * Displays the launchable agent inventory for one paired gateway and
 * lets the user add specific agents into the main server list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GatewayAgentsScreen(
    onNavigateBack: () -> Unit,
    viewModel: GatewayAgentsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingAddAgent by rememberSaveable { mutableStateOf<GatewayAgent?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    pendingAddAgent?.let { agent ->
        var acknowledgedRisk by rememberSaveable(agent.id) { mutableStateOf(false) }
        val gatewayHost = uiState.gateway?.host.orEmpty()
        val riskLines = addAgentRiskLines(LocalContext.current.resources, agent, gatewayHost)
        AlertDialog(
            onDismissRequest = { pendingAddAgent = null },
            title = { Text(stringResource(R.string.serverlist_gateway_agents_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.serverlist_gateway_agents_add_body, agent.displayName),
                    )
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
                            stringResource(R.string.serverlist_gateway_agents_acknowledge_risk),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = acknowledgedRisk,
                    onClick = {
                        viewModel.addAgent(agent)
                        pendingAddAgent = null
                    },
                ) {
                    Text(stringResource(R.string.serverlist_gateway_agents_add_btn))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingAddAgent = null }) {
                    Text(stringResource(R.string.serverlist_gateway_agents_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.gateway?.name ?: stringResource(R.string.serverlist_gateway_agents_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.serverlist_back_desc))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularWavyProgressIndicator(modifier = Modifier.size(64.dp))
            }
            return@Scaffold
        }

        uiState.loadError?.let { message ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(320.dp),
                    )
                    OutlinedButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.serverlist_gateway_agents_retry))
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(uiState.agents, key = { it.id }) { agent ->
                val alreadyAdded = agent.id in uiState.addedAgentIds
                val canAdd = agent.manifestValid && !alreadyAdded

                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .then(
                                if (canAdd) {
                                    Modifier.clickable { pendingAddAgent = agent }
                                } else {
                                    Modifier
                                },
                            ),
                    shape = RoundedCornerShape(24.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (alreadyAdded) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            agent.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            agent.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        agent.hint?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CompactAgentChip(label = if (agent.detected) stringResource(R.string.serverlist_gateway_agents_detected) else stringResource(R.string.serverlist_gateway_agents_not_detected))
                            CompactAgentChip(label = if (agent.manifestValid) stringResource(R.string.serverlist_gateway_agents_valid) else stringResource(R.string.serverlist_gateway_agents_invalid))
                            agent.runtimeStatus?.let { CompactAgentChip(label = it) }
                            if (alreadyAdded) {
                                CompactAgentChip(
                                    label = stringResource(R.string.serverlist_gateway_agents_added),
                                    leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                                )
                            }
                        }
                        if (!alreadyAdded && !canAdd) {
                            Text(
                                text = stringResource(R.string.serverlist_gateway_agents_invalid_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAgentChip(
    label: String,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    AssistChip(
        onClick = {},
        enabled = false,
        modifier = Modifier.height(28.dp),
        leadingIcon = leadingIcon,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        },
        shape = RoundedCornerShape(8.dp),
    )
}
