package com.tamimarafat.ferngeist

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import kotlin.math.roundToInt

private const val SESSIONS_ROUTE_PREFIX = "sessions/"
private const val PROJECT_ROUTE_PREFIX = "project/"
private const val CHAT_ROUTE_PREFIX = "chat/"
private const val SERVER_LIST_ROUTE = "server_list"

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnterTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
): EnterTransition =
    when {
        isSharedBoundsTransition() -> EnterTransition.None
        // One sheet: the incoming chat starts against the edge of the released one and both
        // cover the same remaining distance. A crossfade over the slide only ghosted the two.
        isChatChatTransition() -> {
            val remaining = chatChatRemaining()
            slideIntoContainer(
                towards = chatChatDirection(),
                animationSpec = navSpring,
                initialOffset = { (it * remaining).roundToInt() },
            )
        }
        else ->
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = navSpring,
            ) + fadeIn(animationSpec = navFadeSpring)
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navExitTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
): ExitTransition =
    when {
        isSharedBoundsTransition() -> ExitTransition.None
        isChatChatTransition() -> {
            // The released chat is already offset by the drag, so it only has the rest to go.
            val remaining = chatChatRemaining()
            slideOutOfContainer(
                towards = chatChatDirection(),
                animationSpec = navSpring,
                targetOffset = { (it * remaining).roundToInt() },
            )
        }
        else ->
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = navSpring,
            ) + fadeOut(animationSpec = navFadeSpring)
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopEnterTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
): EnterTransition =
    if (isSharedBoundsTransition()) {
        EnterTransition.None
    } else {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = navSpring,
        ) + fadeIn(animationSpec = navFadeSpring)
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExitTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
): ExitTransition =
    if (isSharedBoundsTransition()) {
        ExitTransition.None
    } else {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = navSpring,
        ) + fadeOut(animationSpec = navFadeSpring)
    }

/**
 * Pairs whose screen grows out of the tapped card: the default slide/fade is suppressed so the
 * shared bounds inside the two screens drive the change instead.
 */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.isSharedBoundsTransition(): Boolean =
    isSessionChatTransition() || isServerListSessionsTransition() || isProjectTransition()

/** Project screens share bounds with both their home row and their chat cards. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isProjectTransition(): Boolean {
    val fromRoute = initialState.destination.route ?: return false
    val toRoute = targetState.destination.route ?: return false
    val otherRoute =
        when {
            fromRoute.startsWith(PROJECT_ROUTE_PREFIX) -> toRoute
            toRoute.startsWith(PROJECT_ROUTE_PREFIX) -> fromRoute
            else -> return false
        }
    return otherRoute == SERVER_LIST_ROUTE || otherRoute.startsWith(CHAT_ROUTE_PREFIX)
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.isSessionChatTransition(): Boolean {
    val fromRoute = initialState.destination.route ?: return false
    val toRoute = targetState.destination.route ?: return false
    return (fromRoute.startsWith(SESSIONS_ROUTE_PREFIX) && toRoute.startsWith(CHAT_ROUTE_PREFIX)) ||
        (fromRoute.startsWith(CHAT_ROUTE_PREFIX) && toRoute.startsWith(SESSIONS_ROUTE_PREFIX))
}

/**
 * Returns `true` when navigating between `server_list` and `sessions/{id}`.
 */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.isServerListSessionsTransition(): Boolean {
    val fromRoute = initialState.destination.route ?: return false
    val toRoute = targetState.destination.route ?: return false
    return (fromRoute == SERVER_LIST_ROUTE && toRoute.startsWith(SESSIONS_ROUTE_PREFIX)) ||
        (fromRoute.startsWith(SESSIONS_ROUTE_PREFIX) && toRoute == SERVER_LIST_ROUTE)
}
