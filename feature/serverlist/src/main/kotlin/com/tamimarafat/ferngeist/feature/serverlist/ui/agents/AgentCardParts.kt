package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.ServerNameSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.ServerSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.PressSurface
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.sharedCardBounds

/** The device line sits a step below the title, whatever tone the card carries. */
private const val SERVER_SUBTITLE_ALPHA = 0.75f

/**
 * The pressable shell every agent card shares, over the tabs' common press surface: the
 * agent's actions menu, delete confirmation, and the shared bounds the session list grows out of.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AgentSurface(
    target: LaunchableTarget,
    shape: RoundedCornerShape,
    color: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    if (showDeleteDialog) {
        ServerCardDeleteDialog(
            serverName = target.name,
            onConfirm = onDelete,
            onDismiss = { showDeleteDialog = false },
        )
    }
    Box(modifier) {
        PressSurface(
            shape = shape,
            color = color,
            contentColor = contentColor,
            enabled = enabled,
            onClick = onClick,
            onLongClick = { showMenu = true },
            sharedBoundsKey = ServerSharedBoundsKey(target.id),
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
            content = content,
        )
        ServerCardActionsMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            interactionSource = remember { MutableInteractionSource() },
            isGatewayAgent = target is LaunchableTarget.GatewayAgent,
            onEdit = onEdit,
            onDelete = { showDeleteDialog = true },
        )
    }
}

/** Agent name that the session list's title grows out of. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AgentName(
    target: LaunchableTarget,
    style: TextStyle,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    maxLines: Int = 1,
) {
    Text(
        text = target.name,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.sharedCardBounds(
                ServerNameSharedBoundsKey(target.id),
                sharedTransitionScope,
                animatedContentScope,
            ),
    )
}

@Composable
internal fun ServerCardDeleteDialog(
    serverName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
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
    )
}

@Composable
internal fun ServerCardActionsMenu(
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
    // The card's paired content colour, softened: a fixed surface role drops out on a tinted card.
    val subtitle = LocalContentColor.current.copy(alpha = SERVER_SUBTITLE_ALPHA)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasSavedAuthMethod) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.serverlist_add_server_stored_auth),
                modifier = Modifier.size(14.dp),
                tint = subtitle,
            )
        }

        when (server) {
            is LaunchableTarget.GatewayAgent -> {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = stringResource(R.string.serverlist_card_from_gateway),
                    modifier = Modifier.size(14.dp),
                    tint = subtitle,
                )
                Text(
                    text = server.gatewaySource.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitle,
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
                    color = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal data class ServerConnectionUiState(
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
            val isFailed = isConnecting && uiState.connectionState is ChatConnectionState.Failed
            return ServerConnectionUiState(
                isConnecting = isConnecting,
                isConnected = isConnected,
                isFailed = isFailed,
            )
        }
    }
}
