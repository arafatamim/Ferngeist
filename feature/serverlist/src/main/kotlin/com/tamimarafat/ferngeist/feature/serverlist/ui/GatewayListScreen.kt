package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.feature.serverlist.GatewayListViewModel

/**
 * Shows paired gateways only. Launchable gateway-backed agents are
 * managed from the gateway detail flow, not shown directly on this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayListScreen(
    onNavigateBack: () -> Unit,
    onPairAnother: () -> Unit,
    onEditGateway: (GatewaySource) -> Unit,
    onOpenGatewayAgents: (String) -> Unit,
    viewModel: GatewayListViewModel,
) {
    val gateways by viewModel.gateways.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ferngeist Gateways") },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onPairAnother) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(gateways, key = { it.id }) { gateway ->
                GatewayCard(
                    gateway = gateway,
                    onOpenGatewayAgents = { onOpenGatewayAgents(gateway.id) },
                    onEditGateway = { onEditGateway(gateway) },
                    onDeleteGateway = { viewModel.deleteGateway(gateway) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GatewayCard(
    gateway: GatewaySource,
    onOpenGatewayAgents: () -> Unit,
    onEditGateway: () -> Unit,
    onDeleteGateway: () -> Unit,
) {
    var showActionsMenu by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardCornerRadius by animateDpAsState(
        targetValue = if (isPressed) 28.dp else 20.dp,
        label = "gatewayCardCornerRadius",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            shape = RoundedCornerShape(cardCornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onOpenGatewayAgents,
                    onLongClick = { showActionsMenu = true },
                ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(gateway.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${gateway.scheme}://${gateway.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                gateway.gatewayRemoteMode?.let {
                    Text(
                        text = "Remote mode: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DropdownMenuPopup(
            expanded = showActionsMenu,
            onDismissRequest = { showActionsMenu = false },
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                interactionSource = interactionSource,
            ) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        showActionsMenu = false
                        onEditGateway()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showActionsMenu = false
                        onDeleteGateway()
                    }
                )
            }
        }
    }
}
