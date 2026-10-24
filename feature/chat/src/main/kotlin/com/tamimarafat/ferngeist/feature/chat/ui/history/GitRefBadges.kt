package com.tamimarafat.ferngeist.feature.chat.ui.history

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.feature.chat.ui.git.MAX_REF_CHARS

/** Current branch first, then local branches, tags, detached HEAD, remotes. */
internal val refKindOrder =
    listOf(ChatGitRef.Kind.BRANCH, ChatGitRef.Kind.TAG, ChatGitRef.Kind.HEAD, ChatGitRef.Kind.REMOTE)

internal fun ChatGitRef.order(): Int = if (isHead) -1 else refKindOrder.indexOf(kind)

@Composable
internal fun RefBadge(
    ref: ChatGitRef,
    /** Cut long names short, for rows with no room to spare. */
    truncate: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val (container, content) =
        when {
            ref.isHead -> colors.primaryContainer to colors.onPrimaryContainer
            ref.kind == ChatGitRef.Kind.TAG -> colors.tertiaryContainer to colors.onTertiaryContainer
            ref.kind == ChatGitRef.Kind.REMOTE -> colors.surfaceVariant to colors.onSurfaceVariant
            ref.kind == ChatGitRef.Kind.HEAD -> colors.secondaryContainer to colors.onSecondaryContainer
            else -> Color.Transparent to colors.onSurface
        }
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier =
            if (ref.kind == ChatGitRef.Kind.BRANCH && !ref.isHead) {
                Modifier.border(1.dp, colors.outline, shape)
            } else {
                Modifier
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            if (ref.kind == ChatGitRef.Kind.TAG) {
                Icon(Icons.Outlined.Sell, contentDescription = null, modifier = Modifier.size(12.dp))
            }
            Text(
                text = if (truncate) ref.name.ellipsize(MAX_REF_CHARS) else ref.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun String.ellipsize(max: Int): String = if (length <= max) this else take(max - 1) + "…"

@Composable
internal fun MoreRefsBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
