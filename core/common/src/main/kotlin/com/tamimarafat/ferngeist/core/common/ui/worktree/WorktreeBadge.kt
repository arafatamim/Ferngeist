package com.tamimarafat.ferngeist.core.common.ui.worktree

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R

/**
 * Marks a chat as running in a worktree, labelled with its [branch] plus whatever git could
 * tell us about it: [ahead] commits and uncommitted work. The whole thing is a label, not a
 * control: removing the worktree lives in the card's long-press menu.
 */
@Composable
fun WorktreeBadge(
    branch: String,
    modifier: Modifier = Modifier,
    ahead: Int? = null,
    dirty: Boolean? = null,
) {
    val dirtyLabel = stringResource(R.string.common_worktree_dirty)
    Surface(
        modifier = modifier,
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
                text = branch,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            ahead?.takeIf { it > 0 }?.let {
                Text(
                    text = stringResource(R.string.common_worktree_ahead, it),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (dirty == true) {
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
