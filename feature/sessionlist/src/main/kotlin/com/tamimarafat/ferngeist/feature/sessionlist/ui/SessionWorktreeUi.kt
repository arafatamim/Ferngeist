package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val ahead = worktree.ahead?.takeIf { it > 0 }
    val dirtyLabel = stringResource(R.string.sessionlist_worktree_dirty)
    Surface(
        modifier = Modifier.padding(top = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.AccountTree, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = worktree.branch,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            ahead?.let {
                Text(
                    text = stringResource(R.string.sessionlist_worktree_ahead, it),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (worktree.dirty == true) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                        .semantics { contentDescription = dirtyLabel },
                )
            }
        }
    }
}

/** The branch plus whatever git could tell us about it, e.g. `feat-x · ↑2 · uncommitted`. */
@Composable
internal fun worktreeLabel(worktree: GatewayWorktree): String {
    val details =
        buildList {
            worktree.ahead?.takeIf { it > 0 }?.let {
                add(stringResource(R.string.sessionlist_worktree_ahead, it))
            }
            if (worktree.dirty == true) add(stringResource(R.string.sessionlist_worktree_dirty))
        }
    return (listOf(worktree.branch) + details).joinToString(" · ")
}

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
