package com.tamimarafat.ferngeist.workspace

/** The one route both presentations share: the agent list, and the workspace's own screen. */
const val SERVER_LIST_ROUTE: String = "server_list"

private const val SESSIONS_ROUTE_PREFIX = "sessions/"
private const val CHAT_ROUTE_PREFIX = "chat/"

/**
 * What the nav host must do to keep the back stack and the workspace in agreement.
 *
 * The wide workspace holds its selection in [WorkspaceState] — saveable state, not a route — so
 * a window class change, which recreates the activity, has to convert between the two
 * representations. Without that, folding with a chat open dropped the user at the agent list,
 * and unfolding left the full-screen chat route covering the workspace it should be a pane of.
 */
sealed interface WorkspaceRouteAction {
    /** The route already matches the presentation. */
    data object None : WorkspaceRouteAction

    /** A wide window is showing a compact-only route: the workspace takes its content over. */
    data object AbsorbIntoWorkspace : WorkspaceRouteAction

    /** A compact window still holds a wide selection: reopen it as the compact route. */
    data class RestoreCompact(
        val serverId: String,
        val sessionId: String?,
    ) : WorkspaceRouteAction
}

/**
 * The action reconciling [route] with the presentation [compact] picks.
 *
 * Both branches only fire while the two disagree, so this can run on every recomposition instead
 * of tracking transitions by hand — the workspace selection is restored from saveable state
 * before the first composition after a fold, so there is no transition left to observe by then.
 */
fun workspaceRouteAction(
    compact: Boolean,
    route: String?,
    selectedServerId: String?,
    selectedSessionId: String?,
): WorkspaceRouteAction =
    when {
        !compact && isWorkspaceMirroredRoute(route) -> WorkspaceRouteAction.AbsorbIntoWorkspace

        compact && route == SERVER_LIST_ROUTE && selectedServerId != null ->
            WorkspaceRouteAction.RestoreCompact(selectedServerId, selectedSessionId)

        else -> WorkspaceRouteAction.None
    }

/** True for the routes the wide workspace replaces with its own panes. */
fun isWorkspaceMirroredRoute(route: String?): Boolean =
    route != null &&
        (route.startsWith(SESSIONS_ROUTE_PREFIX) || route.startsWith(CHAT_ROUTE_PREFIX))

/**
 * The compact chat route for [serverId], matching `ChatDestination`. Both the cwd and the title
 * must arrive encoded: `ChatViewModel` decodes them exactly as it does from the route arguments
 * the compact flow passes, and the pane-built view model relies on the same encoding.
 */
fun compactChatRoute(
    serverId: String,
    sessionId: String,
    encodedCwd: String,
    encodedTitle: String,
): String = "chat/$serverId/$sessionId?cwd=$encodedCwd&updatedAt=-1&title=$encodedTitle"

/** The compact session-list route for [serverId]; its name is resolved from the server. */
fun sessionsRouteFor(serverId: String): String = "sessions/$serverId"
