package com.tamimarafat.ferngeist.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRoutingTest {
    private val chatRoute = "chat/{serverId}/{sessionId}?cwd={cwd}&updatedAt={updatedAt}&title={title}"
    private val sessionsRoute = "sessions/{serverId}?create={create}&name={name}"

    @Test
    fun wideWindow_absorbsTheCompactChatRoute() {
        assertEquals(
            WorkspaceRouteAction.AbsorbIntoWorkspace,
            workspaceRouteAction(false, chatRoute, "srv", "sess"),
        )
    }

    @Test
    fun wideWindow_absorbsTheCompactSessionListRoute() {
        assertEquals(
            WorkspaceRouteAction.AbsorbIntoWorkspace,
            workspaceRouteAction(false, sessionsRoute, "srv", null),
        )
    }

    @Test
    fun wideWindow_leavesRoutesTheWorkspaceDoesNotOwn() {
        val routes = listOf("server_list", "add_server", "add_gateway", "gateway_agents/{gatewayId}")
        for (route in routes) {
            assertEquals(
                route,
                WorkspaceRouteAction.None,
                workspaceRouteAction(false, route, "srv", "sess"),
            )
        }
    }

    @Test
    fun compactWindow_reopensTheSelectedChat() {
        assertEquals(
            WorkspaceRouteAction.RestoreCompact("srv", "sess"),
            workspaceRouteAction(true, "server_list", "srv", "sess"),
        )
    }

    @Test
    fun compactWindow_reopensTheSelectedAgent() {
        assertEquals(
            WorkspaceRouteAction.RestoreCompact("srv", null),
            workspaceRouteAction(true, "server_list", "srv", null),
        )
    }

    @Test
    fun compactWindow_withoutASelection_staysWhereItIs() {
        assertEquals(
            WorkspaceRouteAction.None,
            workspaceRouteAction(true, "server_list", null, null),
        )
    }

    @Test
    fun compactWindow_alreadyShowingTheChat_doesNotReopenIt() {
        assertEquals(
            WorkspaceRouteAction.None,
            workspaceRouteAction(true, chatRoute, "srv", "sess"),
        )
    }

    @Test
    fun restoredRoutes_areTheOnesTheWideWindowAbsorbs() {
        // What a narrow window restores must be what a wide one takes back over, or a fold
        // would ping-pong between the two presentations.
        assertTrue(isWorkspaceMirroredRoute(compactChatRoute("srv", "sess", "/tmp/a%20b", "Hello")))
        assertTrue(isWorkspaceMirroredRoute(sessionsRouteFor("srv")))
    }
}
