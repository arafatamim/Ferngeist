package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.gateway.GatewayWorktree

/**
 * Marks a chat as running in a gateway worktree, labelled with its branch plus whatever
 * git could tell us about it. The whole thing is a label, not a control: removing the
 * worktree lives in the card's long-press menu.
 */
@Composable
internal fun WorktreeBadge(worktree: GatewayWorktree) {
    val details =
        buildList {
            worktree.ahead?.takeIf { it > 0 }?.let {
                add(stringResource(R.string.sessionlist_worktree_ahead, it))
            }
            if (worktree.dirty == true) add(stringResource(R.string.sessionlist_worktree_dirty))
        }
    AssistChip(
        onClick = {},
        enabled = false,
        modifier =
            Modifier
                .padding(top = 4.dp)
                .height(24.dp),
        shape = RoundedCornerShape(8.dp),
        label = {
            Text(
                text = (listOf(worktree.branch) + details).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
    )
}

/**
 * Confirm discarding a worktree's uncommitted changes after the gateway refused the
 * removal. Its commits are never at risk: git only deletes the branch once it is merged.
 */
@Composable
internal fun DiscardWorktreeDialog(
    pending: SessionListEvent.ConfirmRemoveWorktree,
    onDismiss: () -> Unit,
    onConfirm: (SessionListEvent.ConfirmRemoveWorktree) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessionlist_worktree_discard_title)) },
        text = { Text(stringResource(R.string.sessionlist_worktree_discard_body, pending.branch)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pending) }, modifier = Modifier.handCursor()) {
                Text(
                    text = stringResource(R.string.sessionlist_worktree_discard_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(R.string.sessionlist_cwd_cancel))
            }
        },
    )
}
