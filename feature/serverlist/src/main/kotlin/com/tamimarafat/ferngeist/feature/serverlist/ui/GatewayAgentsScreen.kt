package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.feature.serverlist.GatewayAgentsUiState
import com.tamimarafat.ferngeist.feature.serverlist.GatewayAgentsViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayAgent

private const val CUSTOM_AGENT_SOURCE = "custom"

/**
 * Displays the launchable agent inventory for one paired gateway and
 * lets the user add specific agents into the main server list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GatewayAgentsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddCustomAgent: () -> Unit,
    viewModel: GatewayAgentsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingAddAgent by rememberSaveable { mutableStateOf<GatewayAgent?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    pendingAddAgent?.let { agent ->
        AddAgentConfirmationDialog(
            agent = agent,
            uiState = uiState,
            onDismiss = { pendingAddAgent = null },
            onConfirm = {
                viewModel.addAgent(agent)
                pendingAddAgent = null
            },
        )
    }

    CustomAgentDeleteDialogHost(
        pending = pendingDelete,
        onConfirm = { stopFirst -> pendingDelete?.let { viewModel.confirmDelete(it.agent, stopFirst) } },
        onDismiss = viewModel::dismissDelete,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.gateway?.name ?: stringResource(R.string.serverlist_gateway_agents_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.serverlist_back_desc),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.loadError == null && (!uiState.isLoading || uiState.agents.isNotEmpty())) {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToAddCustomAgent,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.serverlist_custom_agent_title)) },
                )
            }
        },
    ) { padding ->
        when {
            uiState.isLoading && uiState.agents.isEmpty() ->
                LoadingContent(modifier = Modifier.padding(padding))
            uiState.loadError != null && uiState.agents.isEmpty() -> {
                ErrorContent(
                    message = uiState.loadError.orEmpty(),
                    onRetry = viewModel::refresh,
                    modifier = Modifier.padding(padding),
                )
            }
            else ->
                AgentList(
                    agents = uiState.agents,
                    addedAgentIds = uiState.addedAgentIds,
                    onAgentClick = { pendingAddAgent = it },
                    onDeleteAgent = viewModel::requestDelete,
                    modifier = Modifier.padding(padding),
                )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(modifier = Modifier.size(64.dp))
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ErrorStateCard(
            headline = stringResource(R.string.serverlist_gateway_agents_error_title),
            body = message,
            icon = Icons.Rounded.CloudOff,
            medallionContainer = MaterialTheme.colorScheme.errorContainer,
            medallionContent = MaterialTheme.colorScheme.onErrorContainer,
            medallionShape = MaterialShapes.VerySunny.toShape(),
            ctaLabel = stringResource(R.string.serverlist_gateway_agents_retry),
            onCta = onRetry,
        )
    }
}

@Composable
private fun AgentList(
    agents: List<GatewayAgent>,
    addedAgentIds: Set<String>,
    onAgentClick: (GatewayAgent) -> Unit,
    onDeleteAgent: (GatewayAgent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Custom agents are the user's own entries, so they lead the list in their own
    // section instead of sitting wherever the gateway happens to inject them. The
    // gateway group is labelled too when both groups are present, so the list does
    // not read as one long "Custom agents" section.
    val (customAgents, gatewayAgents) = agents.partition { it.source == CUSTOM_AGENT_SOURCE }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (customAgents.isNotEmpty()) {
            // Headers carry no key on purpose: keys live in the same space as agent ids, so a
            // gateway-supplied id could collide with a header key and break the list.
            item {
                SectionHeader(title = stringResource(R.string.serverlist_section_custom_agents))
            }
            agentItems(customAgents, addedAgentIds, onAgentClick, onDeleteAgent)
        }
        if (gatewayAgents.isNotEmpty()) {
            if (customAgents.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.serverlist_section_built_in_agents))
                }
            }
            agentItems(gatewayAgents, addedAgentIds, onAgentClick, onDeleteAgent)
        }
    }
}

private fun LazyListScope.agentItems(
    agents: List<GatewayAgent>,
    addedAgentIds: Set<String>,
    onAgentClick: (GatewayAgent) -> Unit,
    onDeleteAgent: (GatewayAgent) -> Unit,
) {
    items(agents, key = { it.id }) { agent ->
        Box(Modifier.animateItem()) {
            AgentCard(
                agent = agent,
                alreadyAdded = agent.id in addedAgentIds,
                canAdd = agent.manifestValid && agent.id !in addedAgentIds,
                onClick = { onAgentClick(agent) },
                onDelete = { onDeleteAgent(agent) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgentCard(
    agent: GatewayAgent,
    alreadyAdded: Boolean,
    canAdd: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActionsMenu by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isCustom = agent.source == CUSTOM_AGENT_SOURCE

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .then(
                        when {
                            // Custom agents are deletable at any time, so they carry the long press;
                            // tapping still only adds them when they can be added.
                            isCustom ->
                                Modifier
                                    .combinedClickable(
                                        onClick = { if (canAdd) onClick() },
                                        onLongClick = { showActionsMenu = true },
                                    ).semantics {
                                        contentDescription = agent.displayName
                                    }
                            canAdd ->
                                Modifier
                                    .clickable(onClick = onClick)
                                    .semantics {
                                        contentDescription = agent.displayName
                                    }
                            else ->
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
            AgentCardBody(
                agent = agent,
                alreadyAdded = alreadyAdded,
                canAdd = canAdd,
            )
        }

        if (isCustom) {
            CustomAgentActionsMenu(
                expanded = showActionsMenu,
                onDismiss = { showActionsMenu = false },
                interactionSource = interactionSource,
                onDelete = {
                    showActionsMenu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun CustomAgentActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    interactionSource: MutableInteractionSource,
    onDelete: () -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, 1),
            interactionSource = interactionSource,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.serverlist_custom_agent_delete)) },
                onClick = {
                    onDismiss()
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun AgentCardBody(
    agent: GatewayAgent,
    alreadyAdded: Boolean,
    canAdd: Boolean,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentIconBadge(
                iconUrl = agent.registry?.icon,
                fallback = Icons.Default.SmartToy,
                size = 20.dp,
                containerSize = 32.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                agent.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
        AgentChipRow(
            agent = agent,
            alreadyAdded = alreadyAdded,
        )
        if (!alreadyAdded && !canAdd) {
            Text(
                text = stringResource(R.string.serverlist_gateway_agents_invalid_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (agent.source == CUSTOM_AGENT_SOURCE && !agent.detected) {
            Text(
                text = stringResource(R.string.serverlist_gateway_agents_custom_not_detected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AgentChipRow(
    agent: GatewayAgent,
    alreadyAdded: Boolean,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (agent.source == CUSTOM_AGENT_SOURCE) {
            CompactAgentChip(label = stringResource(R.string.serverlist_gateway_agents_custom))
        }
        CompactAgentChip(
            label =
                if (agent.detected) {
                    stringResource(
                        R.string.serverlist_gateway_agents_detected,
                    )
                } else {
                    stringResource(R.string.serverlist_gateway_agents_not_detected)
                },
        )
        CompactAgentChip(
            label =
                if (agent.manifestValid) {
                    stringResource(
                        R.string.serverlist_gateway_agents_valid,
                    )
                } else {
                    stringResource(R.string.serverlist_gateway_agents_invalid)
                },
        )
        agent.runtimeStatus?.let { CompactAgentChip(label = it) }
        if (alreadyAdded) {
            CompactAgentChip(
                label = stringResource(R.string.serverlist_gateway_agents_added),
                leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun AddAgentConfirmationDialog(
    agent: GatewayAgent,
    uiState: GatewayAgentsUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var acknowledgedRisk by rememberSaveable(agent.id) { mutableStateOf(false) }
    val gatewayHost = uiState.gateway?.host.orEmpty()
    val riskLines = addAgentRiskLines(LocalResources.current, agent, gatewayHost)
    AlertDialog(
        onDismissRequest = onDismiss,
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
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.serverlist_gateway_agents_add_btn))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.serverlist_gateway_agents_cancel))
            }
        },
    )
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

@androidx.compose.ui.tooling.preview.Preview(name = "Gateway agents error", showBackground = true)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GatewayAgentsErrorPreview() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ErrorStateCard(
            headline = stringResource(R.string.serverlist_gateway_agents_error_title),
            body = "Couldn't reach the gateway. Check the connection and retry.",
            icon = Icons.Rounded.CloudOff,
            medallionContainer = MaterialTheme.colorScheme.errorContainer,
            medallionContent = MaterialTheme.colorScheme.onErrorContainer,
            medallionShape = MaterialShapes.VerySunny.toShape(),
            ctaLabel = stringResource(R.string.serverlist_gateway_agents_retry),
            onCta = {},
        )
    }
}
