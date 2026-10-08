package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
 * gateway process is still leased, Delete when the agent advertised
 * `session/delete`. Nothing is ever greyed out — an item with no effect is
 * not shown.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDisconnect: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val itemCount = listOfNotNull(onDisconnect, onDelete).size
    if (!expanded || itemCount == 0) return
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, itemCount),
        ) {
            onDisconnect?.let { disconnect ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_action_disconnect)) },
                    modifier = Modifier.handCursor(),
                    onClick = {
                        onDismiss()
                        disconnect()
                    },
                )
            }
            onDelete?.let { delete ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.sessionlist_action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
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
