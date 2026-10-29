package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.sessionlist.R

/**
 * Long-press menu on a session card, anchored to the card's parent Box and
 * mirroring ServerCardActionsMenu. Each item is nullable and only rendered
 * when it has something to do: Disconnect when a transport is live or a
 * gateway process is still leased, Remove worktree when the chat runs in a
 * managed one, Delete when the agent advertised `session/delete`. Nothing is
 * ever greyed out — an item with no effect is not shown.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    interactionSource: MutableInteractionSource,
    onDisconnect: (() -> Unit)?,
    onRemoveWorktree: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val itemCount = listOfNotNull(onDisconnect, onRemoveWorktree).size
    if (!expanded || itemCount == 0 && onDelete == null) return
    val groupCount = if (itemCount > 0 && onDelete != null) 2 else 1
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (itemCount > 0) {
            StandardSessionActions(onDisconnect, onRemoveWorktree, onDismiss, interactionSource, groupCount)
        }
        onDelete?.let { delete ->
            if (itemCount > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(groupCount - 1, groupCount),
                interactionSource = if (itemCount == 0) interactionSource else null,
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.sessionlist_action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    shape = MenuDefaults.itemShape(0, 1).shape,
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.handCursor(),
                    onClick = {
                        onDismiss()
                        delete()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StandardSessionActions(
    onDisconnect: (() -> Unit)?,
    onRemoveWorktree: (() -> Unit)?,
    onDismiss: () -> Unit,
    interactionSource: MutableInteractionSource,
    groupCount: Int,
) {
    val itemCount = listOfNotNull(onDisconnect, onRemoveWorktree).size
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(0, groupCount),
        interactionSource = interactionSource,
    ) {
        onDisconnect?.let { disconnect ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_action_disconnect)) },
                shape = MenuDefaults.itemShape(0, itemCount).shape,
                leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
                modifier = Modifier.handCursor(),
                onClick = {
                    onDismiss()
                    disconnect()
                },
            )
        }
        onRemoveWorktree?.let { removeWorktree ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_action_remove_worktree)) },
                shape = MenuDefaults.itemShape(if (onDisconnect == null) 0 else 1, itemCount).shape,
                leadingIcon = { Icon(Icons.Default.AccountTree, contentDescription = null) },
                modifier = Modifier.handCursor(),
                onClick = {
                    onDismiss()
                    removeWorktree()
                },
            )
        }
    }
}
