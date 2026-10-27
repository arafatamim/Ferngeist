package com.tamimarafat.ferngeist.feature.sessionlist.project

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.status.ActivityIconContainer
import com.tamimarafat.ferngeist.core.common.ui.status.WorkingSubtitle
import com.tamimarafat.ferngeist.core.common.ui.worktree.WorktreeBadge
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.ui.ChatCard
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionActionsMenu
import com.tamimarafat.ferngeist.feature.sessionlist.ui.sessionSharedBounds
import com.tamimarafat.ferngeist.feature.sessionlist.ui.sessionTitleBounds
import com.tamimarafat.ferngeist.gateway.GatewayWorktree

/** One chat of a project: the agent list's chat card, led by the agent that owns it. */
@Composable
internal fun ProjectChatRow(
    chat: ProjectChat,
    worktree: GatewayWorktree?,
    worktreeBranch: String?,
    live: Boolean,
    working: Boolean,
    removing: Boolean,
    actions: ProjectChatActions,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var showMenu by rememberSaveable(chat.target.id, chat.summary.id) { mutableStateOf(false) }
    val hasMenu = !removing && listOfNotNull(actions.onClose, actions.onRemoveWorktree, actions.onDelete).isNotEmpty()
    val openMenu = {
        if (hasMenu) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showMenu = true
        }
    }
    Box(modifier) {
        // The chat screen grows out of this card, as it does out of the agent list's.
        ChatCard(
            live = live,
            removing = removing,
            onClick = onClick,
            onOpenMenu = openMenu,
            modifier =
                Modifier.sessionSharedBounds(
                    chat.summary.id,
                    sharedTransitionScope,
                    animatedContentScope,
                    sharedBoundsEnabled,
                ),
        ) {
            // Every agent shares its state treatment; the upright logo tells them apart.
            ActivityIconContainer(
                working = working,
                size = 40.dp,
                idleShape = CircleShape,
                modifier = Modifier.semantics { contentDescription = chat.target.name },
            ) {
                AgentIconBadge(
                    target = chat.target,
                    fallback = Icons.Rounded.SmartToy,
                    containerSize = null,
                )
            }
            ProjectChatText(
                chat,
                working,
                worktree,
                worktreeBranch,
                Modifier.weight(1f),
                Modifier.sessionTitleBounds(
                    chat.summary.id,
                    sharedTransitionScope,
                    animatedContentScope,
                    sharedBoundsEnabled,
                ),
            )
        }
        SessionActionsMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onDisconnect = actions.onClose,
            onRemoveWorktree = actions.onRemoveWorktree,
            onDelete = actions.onDelete,
        )
    }
}

@Composable
private fun ProjectChatText(
    chat: ProjectChat,
    working: Boolean,
    worktree: GatewayWorktree?,
    worktreeBranch: String?,
    modifier: Modifier,
    titleModifier: Modifier,
) {
    Column(modifier) {
        Text(
            text = sessionTitleOrNull(chat.summary.title) ?: stringResource(R.string.sessionlist_untitled),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = titleModifier,
        )
        WorkingSubtitle(
            working = working,
            streamKey = "${chat.target.id}/${chat.summary.id}",
        ) {
            // Missing logos need a textual fallback; a known logo already identifies the agent.
            if (chat.target.binding.icon
                    .isNullOrBlank()
            ) {
                Text(
                    text = chat.target.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        worktreeBranch?.let {
            WorktreeBadge(
                branch = it,
                modifier = Modifier.padding(top = 4.dp),
                ahead = worktree?.ahead,
                dirty = worktree?.dirty,
            )
        }
    }
}
