package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.core.common.ui.ConnectionStatusPill
import com.tamimarafat.ferngeist.core.common.ui.sessionTitleSharedBounds
import kotlinx.coroutines.launch

// region: ChatTopBar

/**
 * Wraps [TwoRowsTopAppBar] with a gradient surface-fade background.
 *
 * The background gradient goes from opaque surface (top) to transparent (bottom).
 * As [scrollBehavior.state.collapsedFraction] approaches 1.0, the base surface
 * fades out completely, leaving only the pill elements visible.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun ChatTopBar(
    sessionId: String,
    sessionTitle: String,
    cwd: String?,
    activeModel: String?,
    connectionState: ChatConnectionState,
    totalTokens: Int?,
    contextWindowTokens: Int?,
    costAmount: Double?,
    costCurrency: String?,
    gitAdditions: Int,
    gitDeletions: Int,
    gitBranch: String?,
    gitChangedFiles: Int,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigateBack: () -> Unit,
    onConnectionStatusClick: () -> Unit,
    onGitStatusClick: () -> Unit,
    onGitStatusLongPress: () -> Unit,
    onTitleClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
) {
    // Gradient: opaque surface at top fades to transparent at bottom.
    // As the bar collapses, the base surface fades to reveal the gradient
    // layer underneath, giving a "surface peeling away" effect.
    val collapsedFraction = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
    val expandedBackground = MaterialTheme.colorScheme.surface.copy(alpha = 1f - collapsedFraction)
    val topShadow = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val middleShadow = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    val bottomShadow = Color.Transparent

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(expandedBackground)
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    topShadow,
                                    middleShadow,
                                    bottomShadow,
                                ),
                        ),
                ),
    ) {
        TwoRowsTopAppBar(
            navigationIcon = {
                FilledTonalIconButton(
                    onClick = onNavigateBack,
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.chat_back_desc),
                    )
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
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
            },
            subtitle =
                activeModel?.takeIf { it.isNotBlank() }?.let { model ->
                    { expanded ->
                        if (expanded) {
                            Text(
                                text = model,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                    }
                },
            actions = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (gitAdditions > 0 || gitDeletions > 0 || gitChangedFiles > 0) {
                        GitStatusIndicatorButton(
                            additions = gitAdditions,
                            deletions = gitDeletions,
                            branch = gitBranch,
                            changedFiles = gitChangedFiles,
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
            },
            collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
            expandedHeight =
                if (activeModel.isNullOrBlank()) {
                    TopAppBarDefaults.MediumFlexibleAppBarWithoutSubtitleExpandedHeight + 24.dp
                } else {
                    TopAppBarDefaults.MediumFlexibleAppBarWithSubtitleExpandedHeight + 24.dp
                },
            scrollBehavior = scrollBehavior,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
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
@OptIn(
    ExperimentalSharedTransitionApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
@Composable
internal fun ChatTopBarTitle(
    expanded: Boolean,
    collapsedFraction: Float,
    sessionId: String,
    sessionTitle: String,
    cwd: String?,
    model: String?,
    onTitleClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
) {
    // Both expanded and collapsed forms render, but only one owns the
    // shared-bounds transition at a time. Ownership flips at the 50% mark
    // to avoid both forms trying to drive the same shared element.
    val ownsSharedTitleBounds =
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
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                    )
                },
                text = {
                    Column {
                        cwd?.takeIf { it.isNotBlank() }?.let { value ->
                            Text(
                                text = value,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        model?.takeIf { it.isNotBlank() }?.let { value ->
                            Text(
                                text = value,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                },
            )
        },
        state = tooltipState,
    ) {
        if (expanded) {
            with(sharedTransitionScope) {
                Text(
                    text = sessionTitle,
                    style =
                        MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier =
                        Modifier
                            .then(
                                if (ownsSharedTitleBounds) {
                                    Modifier.sessionTitleSharedBounds(sessionId, sharedTransitionScope, animatedContentScope)
                                } else {
                                    Modifier
                                },
                            ).combinedClickable(
                                onClick = {
                                    tooltipState.dismiss()
                                    onTitleClick()
                                },
                                onLongClick = showTitleTooltip,
                            )
                            .semantics {
                                contentDescription = sessionTitle
                            },
                )
            }
        } else {
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
                        )
                        .semantics {
                            contentDescription = sessionTitle
                        },
            ) {
                with(sharedTransitionScope) {
                    Text(
                        text = sessionTitle,
                        style =
                            MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .then(
                                    if (ownsSharedTitleBounds) {
                                        Modifier.sessionTitleSharedBounds(sessionId, sharedTransitionScope, animatedContentScope)
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }
        }
    }
}
// endregion
