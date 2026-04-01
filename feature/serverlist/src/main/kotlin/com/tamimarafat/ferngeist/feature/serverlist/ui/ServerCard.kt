package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ServerCard(
    server: LaunchableTarget,
    uiState: ServerListUiState,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val connectionState = remember(
        server.id,
        uiState.connectingServerId,
        uiState.connectedServerState,
        uiState.connectionState
    ) {
        ServerConnectionUiState.from(server.id, uiState)
    }
    val actionsMenuInteractionSource = remember { MutableInteractionSource() }

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showActionsMenu by rememberSaveable { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Delete Server") },
            text = { Text("Remove \"${server.name}\" and all its saved sessions?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(28.dp),
        )
    }

    val containerColor by animateColorAsState(
        targetValue = when {
            connectionState.isConnected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            connectionState.isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(400),
        label = "containerColor",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = !connectionState.isConnecting,
                    onClick = onClick,
                    onLongClick = { showActionsMenu = true },
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectingTitleIndicator(visible = connectionState.isConnecting)

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = server.subtitle(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                server.preferredAuthMethodId?.takeIf { it.isNotBlank() }?.let { authMethodId ->
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        label = { Text("Auth: $authMethodId") },
                        shape = RoundedCornerShape(10.dp),
                    )
                }

                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            if (server is LaunchableTarget.HelperAgent) {
                                "Desktop Helper"
                            } else {
                                "Manual ACP"
                            }
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                )
            }
        }

        DropdownMenuPopup(
            expanded = showActionsMenu,
            onDismissRequest = { showActionsMenu = false }
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                interactionSource = actionsMenuInteractionSource,
            ) {
                DropdownMenuItem(
                    text = { Text(if (server is LaunchableTarget.HelperAgent) "Manage" else "Edit") },
                    onClick = {
                        showActionsMenu = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showActionsMenu = false
                        showDeleteDialog = true
                    }
                )
            }
        }
    }
}

private fun LaunchableTarget.subtitle(): String {
    return when (this) {
        is LaunchableTarget.HelperAgent -> "Helper ${helperSource.scheme}://${helperSource.host}"
        is LaunchableTarget.Manual -> "${server.scheme}://${server.host}"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectingTitleIndicator(
    visible: Boolean,
) {
    val slotWidth by animateDpAsState(
        targetValue = if (visible) 42.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 500f),
        label = "indicatorSlotWidth",
    )
    val infiniteTransition = rememberInfiniteTransition(label = "sunny")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sunnyRotation",
    )
    Box(
        modifier = Modifier.width(slotWidth),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = spring(stiffness = 500f)) +
                scaleIn(animationSpec = spring(dampingRatio = 0.62f, stiffness = 500f)),
            exit = fadeOut(animationSpec = spring(stiffness = 500f)) +
                scaleOut(animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f)),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation)
                    .clip(MaterialShapes.VerySunny.toShape())
                    .background(MaterialTheme.colorScheme.secondary)
            )
        }
    }
}

private data class ServerConnectionUiState(
    val isConnecting: Boolean,
    val isConnected: Boolean,
    val isFailed: Boolean,
) {
    companion object {
        fun from(serverId: String, uiState: ServerListUiState): ServerConnectionUiState {
            val isConnecting = uiState.connectingServerId == serverId
            val isConnected = uiState.connectedServerState?.serverId == serverId &&
                    uiState.connectionState is AcpConnectionState.Connected
            val isFailed = isConnecting && uiState.connectionState is AcpConnectionState.Failed
            return ServerConnectionUiState(
                isConnecting = isConnecting,
                isConnected = isConnected,
                isFailed = isFailed
            )
        }
    }
}
