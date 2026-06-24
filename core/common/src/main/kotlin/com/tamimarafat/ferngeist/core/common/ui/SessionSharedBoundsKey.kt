package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable

data class SessionSharedBoundsKey(
    val sessionId: String,
)

data class SessionTitleSharedBoundsKey(
    val sessionId: String,
)

/**
 * Distinct from [SessionTitleSharedBoundsKey] so the server list's recent-session titles
 * transition only into the chat title (which also exposes this key) and never collide with
 * the session-list titles during a server -> session-list navigation.
 */
data class RecentSessionTitleSharedBoundsKey(
    val sessionId: String,
)

/**
 * Applies shared-bounds transitions for both [SessionTitleSharedBoundsKey] and
 * [RecentSessionTitleSharedBoundsKey] to this modifier. Must be called from a
 * composable context (uses [SharedTransitionScope.rememberSharedContentState]).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sessionTitleSharedBounds(
    sessionId: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedContentScope,
): Modifier =
    with(sharedTransitionScope) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(key = SessionTitleSharedBoundsKey(sessionId)),
            animatedVisibilityScope = animatedVisibilityScope,
            enter = fadeIn(),
            exit = fadeOut(),
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        ).sharedBounds(
            sharedContentState = rememberSharedContentState(key = RecentSessionTitleSharedBoundsKey(sessionId)),
            animatedVisibilityScope = animatedVisibilityScope,
            enter = fadeIn(),
            exit = fadeOut(),
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
