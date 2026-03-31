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
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.feature.serverlist.DesktopCompanionListViewModel

/**
 * Shows paired desktop companions only. Launchable helper-backed agents are
 * managed from the companion detail flow, not shown directly on this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopCompanionListScreen(
    onNavigateBack: () -> Unit,
    onPairAnother: () -> Unit,
    onEditCompanion: (ServerConfig) -> Unit,
    onOpenCompanionAgents: (String) -> Unit,
    viewModel: DesktopCompanionListViewModel,
) {
    val companions by viewModel.companions.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Desktop Companions") },
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
            items(companions, key = { it.id }) { companion ->
                DesktopCompanionCard(
                    companion = companion,
                    onOpenCompanionAgents = { onOpenCompanionAgents(companion.id) },
                    onEditCompanion = { onEditCompanion(companion) },
                    onDeleteCompanion = { viewModel.deleteCompanion(companion) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DesktopCompanionCard(
    companion: ServerConfig,
    onOpenCompanionAgents: () -> Unit,
    onEditCompanion: () -> Unit,
    onDeleteCompanion: () -> Unit,
) {
    var showActionsMenu by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardCornerRadius by animateDpAsState(
        targetValue = if (isPressed) 28.dp else 20.dp,
        label = "desktopCompanionCardCornerRadius",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            shape = RoundedCornerShape(cardCornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onOpenCompanionAgents,
                    onLongClick = { showActionsMenu = true },
                ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(companion.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${companion.scheme}://${companion.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                companion.helperRemoteMode?.let {
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
                        onEditCompanion()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showActionsMenu = false
                        onDeleteCompanion()
                    }
                )
            }
        }
    }
}
