package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.serverlist.CustomAgentDelete
import com.tamimarafat.ferngeist.feature.serverlist.R

/** Confirmation, and the stop offer that follows a refused delete. */
@Composable
internal fun CustomAgentDeleteDialogHost(
    pending: CustomAgentDelete?,
    onConfirm: (stopFirst: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (pending) {
        is CustomAgentDelete.Confirm ->
            CustomAgentDeleteDialog(
                title = stringResource(R.string.serverlist_custom_agent_delete_title),
                body = stringResource(R.string.serverlist_custom_agent_delete_body, pending.agent.displayName),
                confirmLabel = stringResource(R.string.serverlist_custom_agent_delete),
                onConfirm = { onConfirm(false) },
                onDismiss = onDismiss,
            )
        is CustomAgentDelete.Running ->
            CustomAgentDeleteDialog(
                title = stringResource(R.string.serverlist_custom_agent_delete_running_title),
                body = stringResource(R.string.serverlist_custom_agent_delete_running_body),
                confirmLabel = stringResource(R.string.serverlist_custom_agent_delete_running_confirm),
                onConfirm = { onConfirm(true) },
                onDismiss = onDismiss,
            )
        null -> Unit
    }
}

@Composable
private fun CustomAgentDeleteDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Delete,
                contentDescription = confirmLabel,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.handCursor()) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
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
