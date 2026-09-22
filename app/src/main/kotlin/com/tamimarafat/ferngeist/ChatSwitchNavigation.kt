package com.tamimarafat.ferngeist

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.tamimarafat.ferngeist.feature.chat.SlideDirection
import com.tamimarafat.ferngeist.feature.chat.SwitcherSession

/**
 * Opens [session] replacing the current chat instead of stacking: A→B→A would
 * otherwise grow the back stack without bound, and back would walk chat
 * history instead of returning to the session list. A drag commit passes its
 * [slideDirection] so the transition continues the finger's direction.
 */
internal fun NavHostController.switchToChat(
    session: SwitcherSession,
    slideDirection: SlideDirection?,
    fallbackTitle: String,
) {
    val encodedCwd = Uri.encode(session.cwd.orEmpty())
    val encodedTitle = Uri.encode(session.title ?: fallbackTitle)
    val updatedAtParam = session.updatedAt ?: -1L
    val slideParam = slideDirection?.let { "&slide=${it.routeValue}" }.orEmpty()
    navigate(
        "chat/${session.serverId}/${session.sessionId}?cwd=$encodedCwd&updatedAt=$updatedAtParam&title=$encodedTitle$slideParam",
    ) {
        currentDestination?.route?.let { currentRoute ->
            popUpTo(currentRoute) { inclusive = true }
        }
        launchSingleTop = true
    }
}

/** True when navigating between two chats: the switcher's directional slide owns this. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.isChatChatTransition(): Boolean {
    val fromRoute = initialState.destination.route ?: return false
    val toRoute = targetState.destination.route ?: return false
    return fromRoute.startsWith("chat/") && toRoute.startsWith("chat/")
}

/** Which way a chat→chat switch slides: right only on an explicit rightward commit. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.chatChatDirection():
    AnimatedContentTransitionScope.SlideDirection {
    val committed =
        targetState.arguments?.getString("slide")?.let { raw ->
            SlideDirection.values().firstOrNull { it.routeValue == raw }
        }
    return if (committed == SlideDirection.RIGHT) {
        AnimatedContentTransitionScope.SlideDirection.Right
    } else {
        AnimatedContentTransitionScope.SlideDirection.Left
    }
}
