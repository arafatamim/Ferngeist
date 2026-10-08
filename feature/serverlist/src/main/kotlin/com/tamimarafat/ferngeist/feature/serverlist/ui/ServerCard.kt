package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.ServerNameSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onSecondaryClick
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun ServerCard(
    server: LaunchableTarget,
    uiState: ServerListUiState,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
    liveServerIds: Set<String> = emptySet(),
) {
    val connectionState = ServerConnectionUiState.from(server.id, uiState, liveServerIds)
    val actionsMenuInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }
    val hasSavedAuthMethod = server.preferredAuthMethodId?.isNotBlank() == true
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showActionsMenu by rememberSaveable { mutableStateOf(false) }

    if (showDeleteDialog) {
        ServerCardDeleteDialog(
            serverName = server.name,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
        )
    }

    val containerColor by animateColorAsState(
        targetValue = connectionState.containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "containerColor",
    )
    val cardCorner by rememberCardCorner(cardInteractionSource)
    val cardShape = RoundedCornerShape(cardCorner)

    Box(modifier = modifier.fillMaxWidth()) {
        ServerCardSurface(
            server = server,
            connectionState = connectionState,
            containerColor = containerColor,
            cardInteractionSource = cardInteractionSource,
            cardShape = cardShape,
            hasSavedAuthMethod = hasSavedAuthMethod,
            onClick = onClick,
            onLongClick = { showActionsMenu = true },
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
        )

        ServerCardActionsMenu(
            expanded = showActionsMenu,
            onDismiss = { showActionsMenu = false },
            interactionSource = actionsMenuInteractionSource,
            isGatewayAgent = server is LaunchableTarget.GatewayAgent,
            onEdit = {
                showActionsMenu = false
                onEdit()
            },
            onDelete = {
                showActionsMenu = false
                showDeleteDialog = true
            },
        )
    }
}

@Composable
private fun ServerCardSurface(
    server: LaunchableTarget,
    connectionState: ServerConnectionUiState,
    containerColor: Color,
    cardShape: RoundedCornerShape,
    cardInteractionSource: MutableInteractionSource,
    hasSavedAuthMethod: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .handCursor()
                .onSecondaryClick(enabled = !connectionState.isConnecting) { onLongClick() }
                .combinedClickable(
                    interactionSource = cardInteractionSource,
                    indication = LocalIndication.current,
                    enabled = !connectionState.isConnecting,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    role = Role.Button,
                ).semantics {
                    contentDescription = server.name
                },
        shape = cardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ServerCardTitleRow(
                server = server,
                containerColor = containerColor,
                isConnecting = connectionState.isConnecting,
                hasSavedAuthMethod = hasSavedAuthMethod,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        }
    }
}

@Composable
private fun ServerCardTitleRow(
    server: LaunchableTarget,
    containerColor: Color,
    isConnecting: Boolean,
    hasSavedAuthMethod: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentIconBadge(
            target = server,
            fallback = Icons.Default.SmartToy,
            size = 18.dp,
            containerSize = 28.dp,
            // The frame follows the card's own tint, one shade darker, so a
            // connected or failed card reads as one surface instead of a neutral
            // chip sitting on a colored one.
            containerColor = containerColor.darken(BADGE_FRAME_DARKEN),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            loading = isConnecting,
        )
        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            with(sharedTransitionScope) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.sharedBounds(
                            sharedContentState =
                                rememberSharedContentState(
                                    key = ServerNameSharedBoundsKey(server.id),
                                ),
                            animatedVisibilityScope = animatedContentScope,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                        ),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            ServerSubtitle(
                server = server,
                hasSavedAuthMethod = hasSavedAuthMethod,
            )
        }
    }
}

/** How far the badge frame is darkened from the card behind it. */
private const val BADGE_FRAME_DARKEN = 0.06f

/** Darkens a color by [amount] toward black, keeping its alpha. */
private fun Color.darken(amount: Float): Color =
    Color(
        red = red * (1f - amount),
        green = green * (1f - amount),
        blue = blue * (1f - amount),
        alpha = alpha,
    )

/** Press feedback: corners spring in toward a squarer shape, matching the hero card. */
@Composable
private fun rememberCardCorner(interactionSource: MutableInteractionSource): State<Dp> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateDpAsState(
        targetValue = if (pressed) 12.dp else 24.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "cardCorner",
    )
}

private val ServerConnectionUiState.containerColor: Color
    @Composable
    get() =
        when {
            isConnected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        }

@Composable
private fun ServerCardDeleteDialog(
    serverName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.serverlist_card_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.serverlist_card_delete_title)) },
        text = { Text(stringResource(R.string.serverlist_card_delete_body, serverName)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(R.string.serverlist_card_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(R.string.serverlist_cancel))
            }
        },
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun ServerCardActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    interactionSource: MutableInteractionSource,
    isGatewayAgent: Boolean,
    onEdit: () -> Unit,
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
                text = {
                    Text(
                        stringResource(
                            if (isGatewayAgent) {
                                R.string.serverlist_card_manage
                            } else {
                                R.string.serverlist_card_edit
                            },
                        ),
                    )
                },
                modifier = Modifier.handCursor(),
                onClick = {
                    onDismiss()
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.serverlist_card_delete)) },
                modifier = Modifier.handCursor(),
                onClick = {
                    onDismiss()
                    onDelete()
                },
            )
        }
    }
}

@Composable
internal fun ServerSubtitle(
    server: LaunchableTarget,
    hasSavedAuthMethod: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasSavedAuthMethod) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.serverlist_add_server_stored_auth),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (server) {
            is LaunchableTarget.GatewayAgent -> {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = stringResource(R.string.serverlist_card_from_gateway),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = server.gatewaySource.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            is LaunchableTarget.Manual -> {
                Text(
                    text =
                        stringResource(
                            R.string.serverlist_gateway_url_format,
                            server.server.scheme,
                            server.server.host,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class ServerConnectionUiState(
    val isConnecting: Boolean,
    val isConnected: Boolean,
    val isFailed: Boolean,
) {
    companion object {
        fun from(
            serverId: String,
            uiState: ServerListUiState,
            liveServerIds: Set<String> = emptySet(),
        ): ServerConnectionUiState {
            val isConnecting = uiState.connectingServerId == serverId
            // One source of truth: a hub-tracked chat holding a live transport.
            // The home VM's own verification socket hangs up after every tap,
            // so it must not drive the dot.
            val isConnected = serverId in liveServerIds
            val isFailed = isConnecting && uiState.connectionState is AcpConnectionState.Failed
            return ServerConnectionUiState(
                isConnecting = isConnecting,
                isConnected = isConnected,
                isFailed = isFailed,
            )
        }
    }
}
