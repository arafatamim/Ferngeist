package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.SessionSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.SessionTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onSecondaryClick
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.cwdKey
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.max

@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun SessionListContent(
    padding: PaddingValues,
    state: SessionListState,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    onRemoveWorktree: (String, String) -> Unit,
    onCloseSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    createSession: () -> Unit,
    onChatOpened: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
) {
    when {
        // Unknown is a third state, not a flavour of empty. While the cache has
        // not answered there is nothing truthful to draw — a spinner claims a
        // wait the user never feels (the rows are already on disk), and the
        // empty state claims "no sessions" when the list is merely unread. So
        // paint neither: the rows or the empty state arrive on the next frame,
        // and with no spinner there is no flash on the way there.
        !state.cacheRead -> {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }

        state.sessions.isEmpty() -> {
            SessionListEmptyContent(
                padding = padding,
                supportsSessionList = state.supportsSessionList,
                onCreateSession = createSession,
            )
        }

        else -> {
            val groupedSessions = groupSessionsByDate(state.sessions)
            SessionListLazyColumn(
                groupedSessions = groupedSessions,
                sessionCount = state.sessions.size,
                padding = padding,
                liveSessionIds = state.liveSessionIds,
                onCloseSession = onCloseSession,
                onDeleteSession = onDeleteSession,
                canDeleteSession = state.supportsSessionDelete,
                onNavigateToChat = onNavigateToChat,
                onRemoveWorktree = onRemoveWorktree,
                worktrees = state.worktrees,
                onChatOpened = onChatOpened,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                sharedBoundsEnabled = sharedBoundsEnabled,
            )
        }
    }
}

@Composable
private fun groupSessionsByDate(sessions: List<SessionSummary>): Map<String, List<SessionSummary>> {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    val locale = LocalLocale.current
    val dateFormatter =
        remember(locale) {
            SimpleDateFormat("MMMM d, yyyy", locale.platformLocale)
        }
    val sortedSessions =
        sessions.sortedWith(
            compareByDescending<SessionSummary> { it.updatedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.id },
        )
    val groupedSessions = linkedMapOf<String, List<SessionSummary>>()
    // Group sessions by calendar date, sorted newest-first.
    // Sessions with updatedAt are bucketed by local date; those without
    // go into a final "Unknown" bucket.
    val withDate =
        sortedSessions.filter { it.updatedAt != null }.groupBy { session ->
            val updatedAt = session.updatedAt ?: 0L
            Instant.ofEpochMilli(updatedAt).atZone(zoneId).toLocalDate()
        }
    withDate.entries
        .sortedByDescending { it.key }
        .forEach { (sessionDate, groupSessions) ->
            // Show "Today" / "Yesterday" for recent dates, formatted date otherwise.
            val label =
                when (sessionDate) {
                    today ->
                        stringResource(
                            R.string.sessionlist_today,
                        )
                    today.minusDays(
                        1,
                    ),
                    ->
                        stringResource(
                            R.string.sessionlist_yesterday,
                        )
                    else -> {
                        val epoch =
                            max(
                                groupSessions.firstOrNull()?.updatedAt ?: 0L,
                                0L,
                            )
                        dateFormatter.format(Date(epoch))
                    }
                }
            groupedSessions[label] = groupSessions
        }
    val unknownSessions = sortedSessions.filter { it.updatedAt == null }
    if (unknownSessions.isNotEmpty()) {
        groupedSessions[
            stringResource(
                R.string.sessionlist_unknown_date,
            ),
        ] =
            unknownSessions
    }
    return groupedSessions
}

@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun SessionListLazyColumn(
    groupedSessions: Map<String, List<SessionSummary>>,
    sessionCount: Int,
    padding: PaddingValues,
    liveSessionIds: Set<String>,
    onCloseSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    canDeleteSession: Boolean,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    onRemoveWorktree: (String, String) -> Unit,
    worktrees: List<GatewayWorktree>?,
    onChatOpened: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
) {
    // A chat opened in a worktree has the worktree directory as its cwd, so the path is the
    // join key — compared the way the cwd filter does, since the agent and the gateway may
    // spell it with different separators or case. Built once per list instead of per row.
    val worktreesByPath = remember(worktrees) { worktrees?.associateBy { cwdKey(it.path) }.orEmpty() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groupedSessions.forEach { (group, groupSessions) ->
            item {
                Text(
                    text = group,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(groupSessions, key = { it.id }) { session ->
                Box(Modifier.animateItem()) {
                    SessionCard(
                        session = session,
                        isLive = session.id in liveSessionIds,
                        worktree = session.cwd?.let { worktreesByPath[cwdKey(it)] },
                        onRemoveWorktree =
                            session.cwd?.let { worktreesByPath[cwdKey(it)] }?.let { worktree ->
                                { onRemoveWorktree(session.id, worktree.id) }
                            },
                        onDisconnect =
                            if (session.id in liveSessionIds || session.gatewaySessionId != null) {
                                { onCloseSession(session.id) }
                            } else {
                                null
                            },
                        onDelete =
                            if (canDeleteSession) {
                                { onDeleteSession(session.id) }
                            } else {
                                null
                            },
                        onClick = {
                            onChatOpened()
                            onNavigateToChat(
                                session.id,
                                session.cwd ?: "",
                                session.updatedAt,
                                session.title,
                            )
                        },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        sharedBoundsEnabled = sharedBoundsEnabled,
                    )
                }
            }
        }
        item {
            Text(
                text =
                    LocalResources.current.getQuantityString(
                        R.plurals.sessionlist_session_count,
                        sessionCount,
                        sessionCount,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Tappable card for a single session in the list.
 *
 * Uses [sharedBounds] for a shared-element transition to the chat screen,
 * keyed by [SessionSharedBoundsKey] (outer card) and [SessionTitleSharedBoundsKey] (title text).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun RowScope.SessionCardText(
    session: SessionSummary,
    worktree: GatewayWorktree?,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
) {
    with(sharedTransitionScope) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sessionTitleOrNull(session.title) ?: stringResource(R.string.sessionlist_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier =
                    if (sharedBoundsEnabled) {
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                sharedContentState =
                                    rememberSharedContentState(
                                        key = SessionTitleSharedBoundsKey(session.id),
                                    ),
                                animatedVisibilityScope = animatedContentScope,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                            )
                        }
                    } else {
                        Modifier
                    },
            )
            session.cwd?.let { cwd ->
                Text(
                    text = cwd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            worktree?.let { WorktreeBadge(it) }
        }
    }
}

@OptIn(
    ExperimentalSharedTransitionApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun SessionCard(
    session: SessionSummary,
    isLive: Boolean,
    worktree: GatewayWorktree?,
    onRemoveWorktree: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
) {
    val haptics = LocalHapticFeedback.current
    var showActionsMenu by rememberSaveable(session.id) { mutableStateOf(false) }
    val hasMenuActions = onDisconnect != null || onDelete != null || onRemoveWorktree != null
    // Right-click is the mouse equivalent of a long press: same menu, same gate.
    val openActionsMenu = {
        if (hasMenuActions) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showActionsMenu = true
        }
    }
    // Live sessions tint the card container exactly like active server cards;
    // the card itself is the status indicator, no trailing dot.
    val containerColor by animateColorAsState(
        targetValue =
            if (isLive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "sessionCardContainer",
    )
    // Two claimants for one key scale the card to the other's bounds; the workspace shows the
    // agent list beside this list, so panes take no bounds at all.
    val boundsModifier =
        if (sharedBoundsEnabled) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState =
                        rememberSharedContentState(
                            key = SessionSharedBoundsKey(session.id),
                        ),
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                )
            }
        } else {
            Modifier
        }
    Box {
        with(sharedTransitionScope) {
            Card(
                modifier =
                    boundsModifier
                        .fillMaxWidth()
                        .clip(CardDefaults.shape)
                        .handCursor()
                        .onSecondaryClick(onClick = openActionsMenu)
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = openActionsMenu,
                        ),
                colors =
                    CardDefaults.cardColors(
                        containerColor = containerColor,
                    ),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SessionCardText(
                        session = session,
                        worktree = worktree,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        sharedBoundsEnabled = sharedBoundsEnabled,
                    )
                }
            }
        }

        SessionActionsMenu(
            expanded = showActionsMenu,
            onDismiss = { showActionsMenu = false },
            onDisconnect = onDisconnect,
            onRemoveWorktree = onRemoveWorktree,
            onDelete = onDelete,
        )
    }
}
