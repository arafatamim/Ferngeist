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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.ConnectionStatusPill
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.isWindowShort
import com.tamimarafat.ferngeist.core.common.ui.motion.rememberPressShape
import com.tamimarafat.ferngeist.core.common.ui.onSecondaryClick
import com.tamimarafat.ferngeist.core.common.ui.sessionTitleSharedBounds
import com.tamimarafat.ferngeist.core.git.ui.GitIndicatorVisibility
import com.tamimarafat.ferngeist.core.git.ui.GitStatusIndicatorButton
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import kotlinx.coroutines.launch
import com.tamimarafat.ferngeist.core.git.R as GitR

// region: ChatTopBar

// The button row: its 40dp controls plus 8dp above and below, so the card beneath keeps an
// 8dp gap from them.
private val MastheadCollapsedHeight = 56.dp

// How far the title slot over-insets the card on the left, and so how far the card is pulled
// back to keep 8dp on both sides.
private val MastheadCardShift = 8.dp

// How far below the bar the fade keeps dissolving into the transcript.
private val MastheadFadeOvershoot = 24.dp

/**
 * The session masthead: a floating card in the same tonal field as its control bubbles, with
 * the title stepping down alongside the collapse.
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
        TopAppBar(
            modifier = Modifier.mastheadFade(),
            expandedHeight = MastheadCollapsedHeight,
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
        return
    }

    // The title steps down with the bar, so the masthead never snaps between two type scales
    // mid-scroll.
    val titleStyle =
        lerp(
            MaterialTheme.typography.headlineSmallEmphasized,
            MaterialTheme.typography.titleLarge,
            collapsedFraction,
        )
    TwoRowsTopAppBar(
        modifier = Modifier.mastheadFade(),
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
        collapsedHeight = MastheadCollapsedHeight,
        // The card's height is its content, so the expanded row is measured from it too:
        // requesting no extra height lets the app bar grow to the card, instead of reserving
        // a fixed block with a hole above the card.
        expandedHeight = MastheadCollapsedHeight,
        scrollBehavior = scrollBehavior,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
    )
}

/** The fade the bar keeps: surface at the top dissolving past its bottom edge into the content. */
@Composable
private fun Modifier.mastheadFade(): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    return this
        .fillMaxWidth()
        .drawBehind {
            val fadeHeight = size.height + MastheadFadeOvershoot.toPx()
            drawRect(
                brush =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                surface.copy(alpha = 0.95f),
                                surface.copy(alpha = 0.8f),
                                Color.Transparent,
                            ),
                        startY = 0f,
                        endY = fadeHeight,
                    ),
                size = Size(size.width, fadeHeight),
            )
        }
}

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

/** The expanded masthead title and its model line inside the bar's bubble-coloured card. */
@Composable
private fun ExpandedTitleCard(
    sessionId: String,
    sessionTitle: String,
    model: String?,
    titleStyle: TextStyle,
    ownsSharedTitleBounds: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
    onTitleClick: () -> Unit,
    showTitleTooltip: () -> Unit,
    tooltipState: TooltipState,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressShape = rememberPressShape(MaterialTheme.shapes.medium, interactionSource)
    Surface(
        modifier =
            Modifier
                .headerCardInset()
                .clip(pressShape)
                .handCursor()
                .onSecondaryClick(onClick = showTitleTooltip)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = {
                        tooltipState.dismiss()
                        onTitleClick()
                    },
                    onLongClick = showTitleTooltip,
                    role = Role.Button,
                ).semantics {
                    contentDescription = sessionTitle
                },
        shape = pressShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ExpandedTitleText(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                titleStyle = titleStyle,
                ownsSharedTitleBounds = ownsSharedTitleBounds,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
            model?.takeIf { it.isNotBlank() }?.let { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The title slot reserves 16dp on the left but only 8dp on the right. Measure the card 8dp wider
 * than the slot and place it 8dp left, so it keeps the same 8dp gap on both sides.
 */
private fun Modifier.headerCardInset(): Modifier =
    layout { measurable, constraints ->
        val shift = MastheadCardShift.roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = constraints.maxWidth + shift,
                    maxWidth = constraints.maxWidth + shift,
                ),
            )
        layout(constraints.maxWidth, placeable.height) {
            placeable.place(-shift, 0)
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
            ExpandedTitleCard(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                model = model,
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
) {
    with(sharedTransitionScope) {
        Text(
            text = sessionTitle,
            style = titleStyle,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.MiddleEllipsis,
            modifier =
                Modifier.then(
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
