package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent

/**
 * Confirm removing a worktree and the chats that ran in it, and — once the gateway or the listing
 * says it has uncommitted changes — discarding those. Its commits are never at risk: git only
 * deletes the branch once it is merged.
 */
@Composable
internal fun RemoveWorktreeDialog(
    pending: SessionListEvent.ConfirmRemoveWorktree,
    onDismiss: () -> Unit,
    onConfirm: (SessionListEvent.ConfirmRemoveWorktree) -> Unit,
) {
    val discard = pending.discardChanges
    val title = if (discard) R.string.sessionlist_worktree_discard_title else R.string.sessionlist_worktree_remove_title
    val body = if (discard) R.string.sessionlist_worktree_discard_body else R.string.sessionlist_worktree_remove_body
    val confirm =
        if (discard) R.string.sessionlist_worktree_discard_confirm else R.string.sessionlist_action_remove_worktree
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            val chats =
                pluralStringResource(R.plurals.sessionlist_worktree_remove_chats, pending.chatCount, pending.chatCount)
            Text(stringResource(body, pending.branch) + "\n\n" + chats)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pending) }, modifier = Modifier.handCursor()) {
                Text(text = stringResource(confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(R.string.sessionlist_cwd_cancel))
            }
        },
    )
}
