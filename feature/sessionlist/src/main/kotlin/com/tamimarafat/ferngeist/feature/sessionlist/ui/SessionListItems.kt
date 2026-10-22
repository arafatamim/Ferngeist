package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.tamimarafat.ferngeist.core.model.cwdKey
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.displayName
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.max

private const val REMOVING_CARD_ALPHA = 0.5f
private const val MISSING_HEADER_KEY = "missing-folders"

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
    val content =
        when {
            !state.cacheRead -> ListContent.Unread
            state.sessions.isEmpty() -> ListContent.Empty
            else -> ListContent.Sessions
        }
    val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val grow = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = content,
        transitionSpec = {
            // The first read lands with no motion: the rows were on disk all along.
            if (initialState == ListContent.Unread) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(enter) + scaleIn(grow, initialScale = 0.92f)) togetherWith fadeOut(exit)
            }
        },
        label = "SessionListContent",
    ) { shown ->
        SessionListContentFor(
            shown,
            padding,
            state,
            onNavigateToChat,
            onRemoveWorktree,
            onCloseSession,
            onDeleteSession,
            createSession,
            onChatOpened,
            sharedTransitionScope,
            animatedContentScope,
            sharedBoundsEnabled,
        )
    }
}

private enum class ListContent { Unread, Empty, Sessions }

@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun SessionListContentFor(
    content: ListContent,
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
    when (content) {
        // Unknown is a third state, not a flavour of empty. While the cache has
        // not answered there is nothing truthful to draw — a spinner claims a
        // wait the user never feels (the rows are already on disk), and the
        // empty state claims "no sessions" when the list is merely unread. So
        // paint neither: the rows or the empty state arrive on the next frame,
        // and with no spinner there is no flash on the way there.
        ListContent.Unread -> {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }

        ListContent.Empty -> {
            SessionListEmptyContent(
                padding = padding,
                supportsSessionList = state.supportsSessionList,
                scopeName =
                    if (state.scopeEnabled &&
                        state.scope != WorktreeScope.All
                    ) {
                        scopeLabel(state.scope, state.scopeOptions)
                    } else {
                        null
                    },
                onCreateSession = createSession,
            )
        }

        ListContent.Sessions -> {
            // Chats whose folder is gone cannot load; they wait at the bottom instead of mixing in.
            val (missing, present) =
                state.sessions.partition { session -> session.cwd?.let(::cwdKey) in state.missingCwds }
            val snackbarScope = rememberCoroutineScope()
            val goneMessage = stringResource(R.string.sessionlist_missing_folder_message)
            SessionListLazyColumn(
                groupedSessions = groupSessionsByDate(present),
                missingSessions = missing,
                onOpenMissing = { snackbarScope.launch { state.snackbarHostState.showSnackbar(goneMessage) } },
                sessionCount = state.sessions.size,
                padding = padding,
                liveSessionIds = state.liveSessionIds,
                onCloseSession = onCloseSession,
                onDeleteSession = onDeleteSession,
                canDeleteSession = state.supportsSessionDelete,
                onNavigateToChat = onNavigateToChat,
                onRemoveWorktree = onRemoveWorktree,
                worktrees = state.worktrees,
                // A directory is picked, so every card shares its path; the badge says which worktree.
                showCwd = state.currentCwd.isNullOrBlank(),
                removingWorktreeIds = state.removingWorktreeIds,
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
    missingSessions: List<SessionSummary>,
    onOpenMissing: () -> Unit,
    sessionCount: Int,
    padding: PaddingValues,
    liveSessionIds: Set<String>,
    onCloseSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    canDeleteSession: Boolean,
    onNavigateToChat: (String, String, Long?, String?) -> Unit,
    onRemoveWorktree: (String, String) -> Unit,
    worktrees: List<GatewayWorktree>?,
    showCwd: Boolean,
    removingWorktreeIds: Set<String>,
    onChatOpened: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
) {
    // A chat opened in a worktree has the worktree directory as its cwd, so the path is the
    // join key — compared the way the cwd filter does, since the agent and the gateway may
    // spell it with different separators or case. Built once per list instead of per row.
    val worktreesByPath = remember(worktrees) { worktrees?.associateBy { cwdKey(it.path) }.orEmpty() }
    val sessionCard: @Composable (SessionSummary, Boolean) -> Unit = { session, gone ->
        val key = session.cwd?.let(::cwdKey)
        // A gone folder is no worktree any more, whatever the listings still say.
        val worktree = worktreesByPath[key]?.takeUnless { gone }
        SessionCard(
            session = session,
            isLive = session.id in liveSessionIds,
            worktree = worktree,
            worktreeBranch = worktree?.displayName,
            worktreeRepo = worktree?.repo,
            // The full path is the only clue to what was deleted.
            showCwd = showCwd || gone,
            gone = gone,
            removing = worktree?.id in removingWorktreeIds,
            onRemoveWorktree = worktree?.let { { onRemoveWorktree(session.id, it.id) } },
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
                if (gone) return@SessionCard onOpenMissing()
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
    var missingExpanded by rememberSaveable { mutableStateOf(false) }
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
                Box(Modifier.animateItem()) { sessionCard(session, false) }
            }
        }
        if (missingSessions.isNotEmpty()) {
            item(key = MISSING_HEADER_KEY) {
                MissingSectionHeader(
                    count = missingSessions.size,
                    expanded = missingExpanded,
                    onToggle = { missingExpanded = !missingExpanded },
                    modifier = Modifier.animateItem(),
                )
            }
            if (missingExpanded) {
                items(missingSessions, key = { it.id }) { session ->
                    Box(Modifier.animateItem()) { sessionCard(session, true) }
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
    worktreeBranch: String?,
    worktreeRepo: String?,
    showCwd: Boolean,
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
            // With a directory picked the badge alone says where; otherwise a worktree chat names its
            // project rather than the worktree's own path.
            val place =
                when {
                    !showCwd -> null
                    worktreeBranch != null -> worktreeRepo?.let(::directoryName)
                    else -> session.cwd
                }
            place?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            worktreeBranch?.let { WorktreeBadge(it, worktree) }
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
    worktreeBranch: String?,
    worktreeRepo: String?,
    showCwd: Boolean,
    gone: Boolean,
    removing: Boolean,
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
    val hasMenuActions = !removing && (onDisconnect != null || onDelete != null || onRemoveWorktree != null)
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
                        .alpha(if (removing || gone) REMOVING_CARD_ALPHA else 1f)
                        .handCursor()
                        .onSecondaryClick(onClick = openActionsMenu)
                        .combinedClickable(
                            enabled = !removing,
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
                        worktreeBranch = worktreeBranch,
                        worktreeRepo = worktreeRepo,
                        showCwd = showCwd,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        sharedBoundsEnabled = sharedBoundsEnabled,
                    )
                    if (removing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
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

/** Toggle for the chats whose folder is gone, collapsed by default so they stay out of the way. */
@Composable
private fun MissingSectionHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "missingSectionChevron",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clip(MaterialTheme.shapes.small)
                .handCursor()
                .clickable(onClickLabel = null, onClick = onToggle)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.sessionlist_missing_folders, count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation),
        )
    }
}
