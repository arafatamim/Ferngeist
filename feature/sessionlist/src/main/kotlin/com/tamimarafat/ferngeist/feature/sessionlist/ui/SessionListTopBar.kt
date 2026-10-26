package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.tamimarafat.ferngeist.core.common.ui.ConnectionStatusPill
import com.tamimarafat.ferngeist.core.common.ui.ServerNameSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.isWindowShort
import com.tamimarafat.ferngeist.feature.sessionlist.R

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun SessionListTopBar(
    state: SessionListState,
    serverId: String,
    titleStyle: TextStyle,
    onShowConnectionStatusDialog: () -> Unit,
    showBackButton: Boolean,
    sharedBoundsEnabled: Boolean,
    onNavigateBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    if (isWindowShort()) {
        TopAppBar(
            title = {
                SessionListTopBarTitle(
                    expanded = false,
                    collapsedFraction = 1f,
                    sharedKey = ServerNameSharedBoundsKey(serverId),
                    title = state.screen.serverName,
                    titleStyle = MaterialTheme.typography.titleLarge,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                    sharedBoundsEnabled = sharedBoundsEnabled,
                )
            },
            navigationIcon = {
                if (showBackButton) {
                    FilledTonalIconButton(onClick = onNavigateBack, modifier = Modifier.handCursor()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription =
                                stringResource(
                                    R.string.sessionlist_back_desc,
                                ),
                        )
                    }
                }
            },
            actions = {
                SessionListTopBarActions(
                    state = state,
                    onShowConnectionStatusDialog = onShowConnectionStatusDialog,
                )
            },
            scrollBehavior = state.screen.scrollBehavior,
        )
        return
    }
    TwoRowsTopAppBar(
        title = { expanded ->
            SessionListTopBarTitle(
                expanded = expanded,
                collapsedFraction =
                    state.screen.scrollBehavior.state.collapsedFraction
                        .coerceIn(0f, 1f),
                sharedKey = ServerNameSharedBoundsKey(serverId),
                title = state.screen.serverName,
                titleStyle = titleStyle,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                sharedBoundsEnabled = sharedBoundsEnabled,
            )
        },
        navigationIcon = {
            if (showBackButton) {
                FilledTonalIconButton(onClick = onNavigateBack, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription =
                            stringResource(
                                R.string.sessionlist_back_desc,
                            ),
                    )
                }
            }
        },
        actions = {
            SessionListTopBarActions(
                state = state,
                onShowConnectionStatusDialog = onShowConnectionStatusDialog,
            )
        },
        collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
        expandedHeight = TopAppBarDefaults.LargeAppBarExpandedHeight,
        scrollBehavior = state.screen.scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListTopBarActions(
    state: SessionListState,
    onShowConnectionStatusDialog: () -> Unit,
) {
    ConnectionStatusPill(
        connectionState = state.connection.connectionState,
        onClick = onShowConnectionStatusDialog,
    )
}

/**
 * Renders a list screen's top-bar title (agent or project) with shared-element transition ownership.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SessionListTopBarTitle(
    expanded: Boolean,
    collapsedFraction: Float,
    sharedKey: Any,
    title: String,
    titleStyle: TextStyle,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean = true,
) {
    val sharedContentState =
        with(sharedTransitionScope) {
            rememberSharedContentState(key = sharedKey)
        }
    // Suppressed while the workspace shows this list beside the agent list. The agent card
    // registers this same key for the same server, and two live claimants make the title scale
    // to the card's bounds as the pair changes — the title visibly resizing as the session list
    // opens. One claimant restores the plain text.
    val ownsSharedTitleBounds =
        sharedBoundsEnabled &&
            if (expanded) {
                collapsedFraction < 0.5f
            } else {
                collapsedFraction >= 0.5f
            }
    val baseModifier =
        if (ownsSharedTitleBounds) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = sharedContentState,
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                )
            }
        } else {
            Modifier
        }

    Text(
        text = title,
        style = titleStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = baseModifier,
    )
}

/**
 * Resolves the display name for the session list top bar.
 *
 * Priority order:
 * 1. [loadedName] if non-blank (server-provided name from the session list response)
 * 2. [navArgName] if non-blank (fallback name passed as a navigation argument)
 * 3. [defaultName] if both are blank or null
 */
internal fun resolveServerDisplayName(
    navArgName: String?,
    loadedName: String?,
    defaultName: String,
): String {
    if (!loadedName.isNullOrBlank()) return loadedName
    if (!navArgName.isNullOrBlank()) return navArgName
    return defaultName
}
