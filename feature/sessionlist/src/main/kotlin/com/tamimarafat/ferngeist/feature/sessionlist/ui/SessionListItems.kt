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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.CardColors
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
import com.tamimarafat.ferngeist.core.common.ui.status.WorkingSubtitle
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusContainerColor
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusContentColor
import com.tamimarafat.ferngeist.core.common.ui.time.rememberPresentationTime
import com.tamimarafat.ferngeist.core.common.ui.worktree.WorktreeBadge
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.cwdKey
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.displayName
import com.tamimarafat.ferngeist.feature.sessionlist.ui.date.datedSessions
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import com.tamimarafat.ferngeist.core.common.ui.chat.ChatCard as SharedChatCard

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
            !state.rows.cacheRead -> ListContent.UNREAD
            state.rows.sessions.isEmpty() -> ListContent.EMPTY
            else -> ListContent.SESSIONS
        }
    val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val grow = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = content,
        transitionSpec = {
            // The first read lands with no motion: the rows were on disk all along.
            if (initialState == ListContent.UNREAD) {
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

private enum class ListContent { UNREAD, EMPTY, SESSIONS }

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
        ListContent.UNREAD -> {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }

        ListContent.EMPTY -> {
            SessionListEmptyContent(
                padding = padding,
                supportsSessionList = state.rows.supportsSessionList,
                scopeName =
                    if (state.repoScope.scopeEnabled &&
                        state.repoScope.scope != WorktreeScope.All
                    ) {
                        scopeLabel(state.repoScope.scope, state.repoScope.scopeOptions)
                    } else {
                        null
                    },
                onCreateSession = createSession,
            )
        }

        ListContent.SESSIONS -> {
            // Chats whose folder is gone cannot load; they wait at the bottom instead of mixing in.
            val (missing, present) =
                state.rows.sessions.partition { session -> session.cwd?.let(::cwdKey) in state.rows.missingCwds }
            val snackbarScope = rememberCoroutineScope()
            val goneMessage = stringResource(R.string.sessionlist_missing_folder_message)
            SessionListLazyColumn(
                groupedSessions = groupSessionsByDate(present, state.screen.isRefreshing),
                missingSessions = missing,
                onOpenMissing = { snackbarScope.launch { state.screen.snackbarHostState.showSnackbar(goneMessage) } },
                sessionCount = state.rows.sessions.size,
                padding = padding,
                liveSessionIds = state.rows.presence.live,
                turnStatuses = state.rows.presence.turnStatuses,
                closableSessionIds = state.rows.presence.closable,
                onCloseSession = onCloseSession,
                onDeleteSession = onDeleteSession,
                canDeleteSession = state.rows.supportsSessionDelete,
                onNavigateToChat = onNavigateToChat,
                onRemoveWorktree = onRemoveWorktree,
                worktrees = state.worktree.worktrees,
                // A directory is picked, so every card shares its path; the badge says which worktree.
                showCwd = state.project.currentCwd.isNullOrBlank(),
                removingWorktreeIds = state.worktree.removingWorktreeIds,
                onChatOpened = onChatOpened,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                sharedBoundsEnabled = sharedBoundsEnabled,
            )
        }
    }
}

@Composable
internal fun groupSessionsByDate(
    sessions: List<SessionSummary>,
    refreshing: Boolean,
): Map<String, List<SessionSummary>> {
    val zoneId = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(rememberPresentationTime(refreshing)).atZone(zoneId).toLocalDate()
    val locale = LocalLocale.current
    val dateFormatter =
        remember(locale) {
            DateFormat.getDateInstance(DateFormat.LONG, locale.platformLocale)
        }
    val groups = datedSessions(sessions, zoneId)
    // Group by local calendar date, newest first, with unknown dates last.
    return groups.mapKeys { (date, group) ->
        // Recent dates use relative labels; older ones follow the user's locale.
        when (date) {
            today -> stringResource(R.string.sessionlist_today)
            today.minusDays(1) -> stringResource(R.string.sessionlist_yesterday)
            null -> stringResource(R.string.sessionlist_unknown_date)
            else -> dateFormatter.format(Date(group.first().updatedAt ?: 0L))
        }
    }
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
    turnStatuses: Map<String, TurnActivity>,
    closableSessionIds: Set<String>,
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
            status = turnStatuses[session.id] ?: TurnActivity(),
            worktree = worktree,
            worktreeBranch = worktree?.displayName,
            worktreeRepo = worktree?.repo,
            // The full path is the only clue to what was deleted.
            showCwd = showCwd || gone,
            gone = gone,
            removing = worktree?.id in removingWorktreeIds,
            onRemoveWorktree = worktree?.let { { onRemoveWorktree(session.id, it.id) } },
            // Close is offered for a live chat, or one the gateway still runs a process for.
            onDisconnect = if (session.id in closableSessionIds) ({ onCloseSession(session.id) }) else null,
            onDelete = deleteAction(session, enabled = canDeleteSession, onDelete = onDeleteSession),
            onClick = {
                if (gone) return@SessionCard onOpenMissing()
                onChatOpened()
                onNavigateToChat(
                    session.id,
                    session.cwd.orEmpty(),
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
                    // Headers are wayfinding, not actions: primary belongs to the New chat button.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    status: TurnActivity,
    worktree: GatewayWorktree?,
    worktreeBranch: String?,
    worktreeRepo: String?,
    showCwd: Boolean,
    gone: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
) {
    with(sharedTransitionScope) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sessionTitleOrNull(session.title) ?: stringResource(R.string.sessionlist_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.sessionTitleBounds(
                        session.id,
                        sharedTransitionScope,
                        animatedContentScope,
                        sharedBoundsEnabled,
                    ),
            )
            // With a directory picked the badge alone says where; otherwise every chat names its
            // project, a worktree's by its repo. A gone folder keeps its full path: the only clue left.
            val place =
                when {
                    !showCwd -> null
                    gone -> session.cwd
                    worktreeBranch != null -> worktreeRepo?.let(::projectName)
                    else -> session.cwd?.let(::projectName)
                }
            // A turn in flight spends the subtitle line on its state; the place returns when unknown.
            WorkingSubtitle(status = status, chatId = session.id) {
                place?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
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
}

private fun deleteAction(
    session: SessionSummary,
    enabled: Boolean,
    onDelete: (String) -> Unit,
): (() -> Unit)? = if (enabled) ({ onDelete(session.id) }) else null

/** The long-press menu has something to offer only while the chat is not being removed. */
private fun menuAvailable(
    removing: Boolean,
    onDisconnect: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onRemoveWorktree: (() -> Unit)?,
): Boolean = !removing && (onDisconnect != null || onDelete != null || onRemoveWorktree != null)

/**
 * Two claimants for one key scale the card to the other's bounds; the workspace shows the
 * agent list beside this list, so panes take no bounds at all.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sessionSharedBounds(
    sessionId: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    enabled: Boolean,
): Modifier =
    if (enabled) {
        with(sharedTransitionScope) {
            this@sessionSharedBounds.sharedBounds(
                sharedContentState =
                    rememberSharedContentState(
                        key = SessionSharedBoundsKey(sessionId),
                    ),
                animatedVisibilityScope = animatedContentScope,
                enter = fadeIn(),
                exit = fadeOut(),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
            )
        }
    } else {
        this
    }

/** The card title's claim on the chat screen's title, gated like [sessionSharedBounds]. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sessionTitleBounds(
    sessionId: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    enabled: Boolean,
): Modifier =
    if (enabled) {
        with(sharedTransitionScope) {
            this@sessionTitleBounds.sharedBounds(
                sharedContentState = rememberSharedContentState(key = SessionTitleSharedBoundsKey(sessionId)),
                animatedVisibilityScope = animatedContentScope,
                enter = fadeIn(),
                exit = fadeOut(),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
            )
        }
    } else {
        this
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
    status: TurnActivity,
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
    val menuInteractionSource = remember { MutableInteractionSource() }
    var showActionsMenu by rememberSaveable(session.id) { mutableStateOf(false) }
    val hasMenuActions = menuAvailable(removing, onDisconnect, onDelete, onRemoveWorktree)
    val openActionsMenu = {
        if (hasMenuActions) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showActionsMenu = true
        }
    }
    Box {
        ChatCard(
            live = isLive,
            updatedAt = session.updatedAt,
            status = status.status,
            removing = removing,
            dimmed = removing || gone,
            onClick = onClick,
            onOpenMenu = openActionsMenu,
            modifier =
                Modifier.sessionSharedBounds(
                    sessionId = session.id,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                    enabled = sharedBoundsEnabled,
                ),
        ) {
            SessionCardText(
                session = session,
                status = status,
                worktree = worktree,
                worktreeBranch = worktreeBranch,
                worktreeRepo = worktreeRepo,
                showCwd = showCwd,
                gone = gone,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                sharedBoundsEnabled = sharedBoundsEnabled,
            )
        }

        SessionActionsMenu(
            expanded = showActionsMenu,
            onDismiss = { showActionsMenu = false },
            interactionSource = menuInteractionSource,
            onDisconnect = onDisconnect,
            onRemoveWorktree = onRemoveWorktree,
            onDelete = onDelete,
        )
    }
}

/**
 * The chat card both the agent's and the project's lists use. A live chat tints the container
 * exactly like an active server card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatCard(
    live: Boolean,
    updatedAt: Long?,
    status: TurnStatus,
    removing: Boolean,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = removing,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = sessionCardColors(live, status)
    SharedChatCard(
        updatedAt = updatedAt,
        containerColor = colors.containerColor,
        contentColor = colors.contentColor,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(CardDefaults.shape)
                .alpha(if (dimmed) REMOVING_CARD_ALPHA else 1f)
                .handCursor()
                // Right-click is the mouse equivalent of a long press: same menu, same gate.
                .onSecondaryClick(onClick = onOpenMenu)
                .combinedClickable(enabled = !removing, onClick = onClick, onLongClick = onOpenMenu),
    ) {
        content()
        if (removing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * The container animates through tones no role matches, so the content color follows the target state.
 */
@Composable
private fun sessionCardColors(
    isLive: Boolean,
    status: TurnStatus,
): CardColors {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue =
            scheme.turnStatusContainerColor(
                status,
                if (isLive) scheme.secondaryContainer else scheme.surfaceContainerHighest,
            ),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "sessionCardContainer",
    )
    return CardDefaults.cardColors(
        containerColor = container,
        contentColor =
            scheme.turnStatusContentColor(
                status,
                if (isLive) scheme.onSecondaryContainer else scheme.onSurface,
            ),
    )
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
