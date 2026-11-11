@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.ConnectionStatusPill
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.isWindowShort
import com.tamimarafat.ferngeist.core.common.ui.onSecondaryClick
import com.tamimarafat.ferngeist.core.common.ui.sessionTitleSharedBounds
import com.tamimarafat.ferngeist.core.git.ui.GitIndicatorVisibility
import com.tamimarafat.ferngeist.core.git.ui.GitStatusIndicatorButton
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import kotlinx.coroutines.launch
import com.tamimarafat.ferngeist.core.git.R as GitR

// region: ChatTopBar

/**
 * The session masthead: a solid tonal block with rounded bottom corners while expanded, handing
 * over to a surface fade as the bar collapses, with the title stepping down alongside it.
 */
@Composable
internal fun ChatTopBar(
    sessionId: String,
    sessionTitle: String,
    cwd: String?,
    activeModel: String?,
    connectionState: ChatConnectionState,
    totalTokens: Long?,
    contextWindowTokens: Long?,
    costAmount: Double?,
    costCurrency: String?,
    gitAdditions: Int,
    gitDeletions: Int,
    gitBranch: String?,
    gitChangedFiles: Int,
    gitRepo: Boolean,
    gitAhead: Int,
    gitBehind: Int,
    worktree: ChatWorktree?,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigateBack: () -> Unit,
    onConnectionStatusClick: () -> Unit,
    onGitStatusClick: () -> Unit,
    onGitStatusLongPress: () -> Unit,
    onTitleClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
    showBackButton: Boolean = true,
    sharedBoundsEnabled: Boolean = true,
) {
    val collapsedFraction = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)

    // Short windows stay on the collapsed single-row bar: the two-row expanded
    // header never pays for its height when vertical space is the constraint.
    if (isWindowShort()) {
        Box(
            modifier = Modifier.mastheadFade(1f),
        ) {
            TopAppBar(
                modifier = Modifier.mastheadBlock(1f),
                navigationIcon = {
                    if (showBackButton) {
                        TopBarBackButton(onNavigateBack)
                    }
                },
                title = {
                    ChatTopBarTitle(
                        expanded = false,
                        collapsedFraction = 1f,
                        sessionId = sessionId,
                        sessionTitle = sessionTitle,
                        cwd = cwd,
                        model = activeModel,
                        onTitleClick = onTitleClick,
                        titleStyle = MaterialTheme.typography.titleLarge,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        sharedBoundsEnabled = sharedBoundsEnabled,
                    )
                },
                actions = {
                    TopBarActions(
                        gitAdditions = gitAdditions,
                        gitDeletions = gitDeletions,
                        gitBranch = gitBranch,
                        gitChangedFiles = gitChangedFiles,
                        gitRepo = gitRepo,
                        gitAhead = gitAhead,
                        gitBehind = gitBehind,
                        worktree = worktree,
                        onGitStatusClick = onGitStatusClick,
                        onGitStatusLongPress = onGitStatusLongPress,
                        connectionState = connectionState,
                        totalTokens = totalTokens,
                        contextWindowTokens = contextWindowTokens,
                        costAmount = costAmount,
                        costCurrency = costCurrency,
                        onConnectionStatusClick = onConnectionStatusClick,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
            )
        }
        return
    }

    // The title steps down with the bar, so the masthead never snaps between two type scales
    // mid-scroll.
    val titleStyle =
        lerp(
            MaterialTheme.typography.headlineSmall,
            MaterialTheme.typography.titleLarge,
            collapsedFraction,
        )
    Box(
        modifier = Modifier.mastheadFade(collapsedFraction),
    ) {
        TwoRowsTopAppBar(
            modifier = Modifier.mastheadBlock(collapsedFraction),
            navigationIcon = {
                if (showBackButton) {
                    TopBarBackButton(onNavigateBack)
                }
            },
            title = { expanded ->
                ChatTopBarTitle(
                    expanded = expanded,
                    collapsedFraction = collapsedFraction,
                    sessionId = sessionId,
                    sessionTitle = sessionTitle,
                    cwd = cwd,
                    model = activeModel,
                    onTitleClick = onTitleClick,
                    titleStyle = titleStyle,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                    sharedBoundsEnabled = sharedBoundsEnabled,
                )
            },
            subtitle =
                activeModel?.takeIf { it.isNotBlank() }?.let { model ->
                    { expanded -> TopBarSubtitleContent(model = model, expanded = expanded) }
                },
            actions = {
                TopBarActions(
                    gitAdditions = gitAdditions,
                    gitDeletions = gitDeletions,
                    gitBranch = gitBranch,
                    gitChangedFiles = gitChangedFiles,
                    gitRepo = gitRepo,
                    gitAhead = gitAhead,
                    gitBehind = gitBehind,
                    worktree = worktree,
                    onGitStatusClick = onGitStatusClick,
                    onGitStatusLongPress = onGitStatusLongPress,
                    connectionState = connectionState,
                    totalTokens = totalTokens,
                    contextWindowTokens = contextWindowTokens,
                    costAmount = costAmount,
                    costCurrency = costCurrency,
                    onConnectionStatusClick = onConnectionStatusClick,
                )
            },
            collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
            // Two lines of the masthead title under the 64dp action row, plus the model line
            // and its breathing space when present. The medium flexible heights only hold one.
            expandedHeight = if (activeModel.isNullOrBlank()) 132.dp else 152.dp,
            scrollBehavior = scrollBehavior,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
        )
    }
}

/**
 * The masthead block: a solid tonal field whose bottom corners flatten as the bar collapses. It
 * fades out entirely with the collapse, handing the bar over to [mastheadFade].
 */
@Composable
private fun Modifier.mastheadBlock(collapsedFraction: Float): Modifier {
    val fraction = collapsedFraction.coerceIn(0f, 1f)
    val corner = (MASTHEAD_CORNER_DP * (1f - fraction)).dp
    return this.background(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f - fraction),
        shape = RoundedCornerShape(bottomStart = corner, bottomEnd = corner),
    )
}

/** The fade the collapsed bar keeps: surface at the top dissolving to nothing at its bottom edge. */
@Composable
private fun Modifier.mastheadFade(collapsedFraction: Float): Modifier {
    val fraction = collapsedFraction.coerceIn(0f, 1f)
    val surface = MaterialTheme.colorScheme.surface
    return this
        .fillMaxWidth()
        .background(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            surface.copy(alpha = 0.95f * fraction),
                            surface.copy(alpha = 0.8f * fraction),
                            Color.Transparent,
                        ),
                ),
        )
}

/** The theme's extraLarge corner, kept as a plain value so it can be interpolated. */
private const val MASTHEAD_CORNER_DP = 28f

@Composable
private fun TopBarBackButton(onNavigateBack: () -> Unit) {
    FilledTonalIconButton(
        onClick = onNavigateBack,
        shape = RoundedCornerShape(percent = 50),
        modifier = Modifier.size(40.dp).handCursor(),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(GitR.string.chat_back_desc),
        )
    }
}

@Composable
private fun TopBarSubtitleContent(
    model: String,
    expanded: Boolean,
) {
    if (expanded) {
        Text(
            text = model,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun TopBarActions(
    gitAdditions: Int,
    gitDeletions: Int,
    gitBranch: String?,
    gitChangedFiles: Int,
    gitRepo: Boolean,
    gitAhead: Int,
    gitBehind: Int,
    worktree: ChatWorktree?,
    onGitStatusClick: () -> Unit,
    onGitStatusLongPress: () -> Unit,
    connectionState: ChatConnectionState,
    totalTokens: Long?,
    contextWindowTokens: Long?,
    costAmount: Double?,
    costCurrency: String?,
    onConnectionStatusClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GitIndicatorVisibility(visible = gitRepo) {
            GitStatusIndicatorButton(
                additions = gitAdditions,
                deletions = gitDeletions,
                branch = gitBranch,
                changedFiles = gitChangedFiles,
                ahead = gitAhead,
                behind = gitBehind,
                worktree = worktree,
                onClick = onGitStatusClick,
                onLongPress = onGitStatusLongPress,
            )
        }
        ConnectionStatusPill(
            connectionState = connectionState,
            totalTokens = totalTokens,
            contextWindowTokens = contextWindowTokens,
            costAmount = costAmount,
            costCurrency = costCurrency,
            onClick = onConnectionStatusClick,
        )
    }
}
// endregion

// region: ChatTopBarTitle

/**
 * Renders the session title in either expanded (plain large text) or collapsed
 * (Surface pill) form, depending on [expanded] and [collapsedFraction].
 *
 * The shared-bounds transition for the session title is assigned to whichever
 * visual form owns the majority of the crossfade — ownership flips at 50%.
 */
@Composable
internal fun ChatTopBarTitle(
    expanded: Boolean,
    collapsedFraction: Float,
    sessionId: String,
    sessionTitle: String,
    cwd: String?,
    model: String?,
    onTitleClick: () -> Unit,
    titleStyle: TextStyle,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
    sharedBoundsEnabled: Boolean = true,
) {
    // Both expanded and collapsed forms render, but only one owns the
    // shared-bounds transition at a time. Ownership flips at the 50% mark
    // to avoid both forms trying to drive the same shared element.
    //
    // Suppressed entirely while another node claims the same key: the workspace's session list
    // registers this key for the same session, and two claimants left the title with nothing
    // to animate to — it rendered as zero pixels, keeping its semantics but no text.
    val ownsSharedTitleBounds =
        sharedBoundsEnabled &&
            if (expanded) {
                collapsedFraction < 0.5f
            } else {
                collapsedFraction >= 0.5f
            }
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val showTitleTooltip: () -> Unit = {
        scope.launch { tooltipState.show() }
    }

    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(
                positioning = TooltipAnchorPosition.Above,
            ),
        tooltip = {
            RichTooltip(
                title = {
                    Text(
                        text = sessionTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                text = {
                    TooltipMetadataRows(cwd = cwd, model = model)
                },
            )
        },
        state = tooltipState,
    ) {
        if (expanded) {
            ExpandedTitleText(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                titleStyle = titleStyle,
                ownsSharedTitleBounds = ownsSharedTitleBounds,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                onTitleClick = onTitleClick,
                showTitleTooltip = showTitleTooltip,
                tooltipState = tooltipState,
            )
        } else {
            CollapsedTitleSurface(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                ownsSharedTitleBounds = ownsSharedTitleBounds,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                onTitleClick = onTitleClick,
                showTitleTooltip = showTitleTooltip,
                tooltipState = tooltipState,
            )
        }
    }
}

@Composable
private fun TooltipMetadataRows(
    cwd: String?,
    model: String?,
) {
    Column {
        cwd?.takeIf { it.isNotBlank() }?.let { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        model?.takeIf { it.isNotBlank() }?.let { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ExpandedTitleText(
    sessionId: String,
    sessionTitle: String,
    titleStyle: TextStyle,
    ownsSharedTitleBounds: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
    onTitleClick: () -> Unit,
    showTitleTooltip: () -> Unit,
    tooltipState: TooltipState,
) {
    with(sharedTransitionScope) {
        Text(
            text = sessionTitle,
            style = titleStyle,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.MiddleEllipsis,
            modifier =
                Modifier
                    .then(
                        if (ownsSharedTitleBounds) {
                            Modifier.sessionTitleSharedBounds(
                                sessionId,
                                sharedTransitionScope,
                                animatedContentScope,
                            )
                        } else {
                            Modifier
                        },
                    ).combinedClickable(
                        onClick = {
                            tooltipState.dismiss()
                            onTitleClick()
                        },
                        onLongClick = showTitleTooltip,
                    ).handCursor()
                    .onSecondaryClick(onClick = showTitleTooltip)
                    .semantics {
                        contentDescription = sessionTitle
                    },
        )
    }
}

@Composable
private fun CollapsedTitleSurface(
    sessionId: String,
    sessionTitle: String,
    ownsSharedTitleBounds: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
    onTitleClick: () -> Unit,
    showTitleTooltip: () -> Unit,
    tooltipState: TooltipState,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(percent = 50))
                .combinedClickable(
                    onClick = {
                        tooltipState.dismiss()
                        onTitleClick()
                    },
                    onLongClick = showTitleTooltip,
                ).handCursor()
                .onSecondaryClick(onClick = showTitleTooltip)
                .semantics {
                    contentDescription = sessionTitle
                },
    ) {
        with(sharedTransitionScope) {
            Text(
                text = sessionTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .then(
                            if (ownsSharedTitleBounds) {
                                Modifier.sessionTitleSharedBounds(
                                    sessionId,
                                    sharedTransitionScope,
                                    animatedContentScope,
                                )
                            } else {
                                Modifier
                            },
                        ),
            )
        }
    }
}
// endregion
