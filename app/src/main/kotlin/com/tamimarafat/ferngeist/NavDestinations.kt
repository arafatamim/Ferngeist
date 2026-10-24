package com.tamimarafat.ferngeist

import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.common.ui.isWindowCompact
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreen
import com.tamimarafat.ferngeist.feature.serverlist.AddCustomAgentViewModel
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.AddServerViewModel
import com.tamimarafat.ferngeist.feature.serverlist.GatewayAgentsViewModel
import com.tamimarafat.ferngeist.feature.serverlist.GatewayListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddCustomAgentScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddGatewayScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddServerScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.GatewayAgentsScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.GatewayListScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.ServerListScreen
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionListScreen
import com.tamimarafat.ferngeist.workspace.ChatViewModelFactory
import com.tamimarafat.ferngeist.workspace.WorkspaceScreen
import com.tamimarafat.ferngeist.workspace.WorkspaceSelection
import com.tamimarafat.ferngeist.workspace.WorkspaceSessionsPane
import com.tamimarafat.ferngeist.workspace.WorkspaceState

private const val SERVER_ID_ARG = "serverId"
private const val ADD_GATEWAY_ROUTE = "add_gateway"

/** What the wide workspace's chat pane is hosted with: the connection hub and the pane's ViewModel factory. */
internal class WorkspacePaneServices(
    val chatConnectionHub: ChatConnectionHub,
    val chatViewModelFactory: ChatViewModelFactory,
)

/** Registers every destination in the app. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun NavGraphBuilder.ferngeistDestinations(
    navController: NavHostController,
    sharedTransitionLayout: SharedTransitionScope,
    workspace: WorkspaceState,
    panes: WorkspacePaneServices,
    onHomeScreenReady: (Boolean) -> Unit,
) {
    serverListDestination(
        navController = navController,
        sharedTransitionLayout = sharedTransitionLayout,
        workspace = workspace,
        panes = panes,
        onHomeScreenReady = onHomeScreenReady,
    )
    gatewaysDestination(navController)
    addServerDestination(navController)
    addGatewayDestination(navController)
    editGatewayDestination(navController)
    gatewayAgentsDestination(navController)
    addCustomAgentDestination(navController)
    editServerDestination(navController)
    sessionsDestination(navController, sharedTransitionLayout)
    chatDestination(navController, sharedTransitionLayout)
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.serverListDestination(
    navController: NavHostController,
    sharedTransitionLayout: SharedTransitionScope,
    workspace: WorkspaceState,
    panes: WorkspacePaneServices,
    onHomeScreenReady: (Boolean) -> Unit,
) {
    composable("server_list") {
        val viewModel: ServerListViewModel = hiltViewModel()

        StartupPermissionsEffect()

        val animatedContentScope = this
        val sharedTransitionScope: SharedTransitionScope = sharedTransitionLayout

        if (isWindowCompact()) {
            CompactServerList(
                navController = navController,
                viewModel = viewModel,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                onHomeScreenReady = onHomeScreenReady,
            )
        } else {
            WorkspaceServerList(
                navController = navController,
                viewModel = viewModel,
                workspace = workspace,
                chatConnectionHub = panes.chatConnectionHub,
                chatViewModelFactory = panes.chatViewModelFactory,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                onHomeScreenReady = onHomeScreenReady,
            )
        }
    }
}

/** The narrow presentation: today's phone flow, where the agent list navigates between routes. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CompactServerList(
    navController: NavHostController,
    viewModel: ServerListViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    onHomeScreenReady: (Boolean) -> Unit,
) {
    ServerListScreen(
        onNavigateToAddServer = { navController.navigate("add_server") },
        onNavigateToPairGateway = { navController.navigate(ADD_GATEWAY_ROUTE) },
        onNavigateToGateways = { navController.navigate("gateways") },
        onNavigateToEditServer = { server ->
            when (server) {
                is LaunchableTarget.GatewayAgent ->
                    navController.navigate("gateway_agents/${server.gatewaySource.id}")
                is LaunchableTarget.Manual -> navController.navigate("edit_server/${server.id}")
            }
        },
        onNavigateToSessions = { serverId, serverName, _, openCreateSessionDialog ->
            val encodedName = Uri.encode(serverName)
            navController.navigate(
                "sessions/$serverId?create=$openCreateSessionDialog&name=$encodedName",
            )
        },
        onResumeSession = { session ->
            val encodedCwd = Uri.encode(session.cwd.orEmpty())
            val encodedTitle = Uri.encode(session.title.orEmpty())
            navController.navigate(
                "chat/${session.serverId}/${session.sessionId}?cwd=$encodedCwd&title=$encodedTitle",
            )
        },
        viewModel = viewModel,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        onHomeScreenReady = onHomeScreenReady,
    )
}

/** The wide presentation: the same list as the workspace's left pane, chat pinned right. */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun WorkspaceServerList(
    navController: NavHostController,
    viewModel: ServerListViewModel,
    workspace: WorkspaceState,
    chatConnectionHub: ChatConnectionHub,
    chatViewModelFactory: ChatViewModelFactory,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    onHomeScreenReady: (Boolean) -> Unit,
) {
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()

    WorkspaceScreen(
        workspace = workspace,
        recentSessions = recentSessions,
        chatConnectionHub = chatConnectionHub,
        chatViewModelFactory = chatViewModelFactory,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = Modifier.fillMaxSize(),
        agentsPane = {
            ServerListScreen(
                onNavigateToAddServer = { navController.navigate("add_server") },
                onNavigateToPairGateway = { navController.navigate(ADD_GATEWAY_ROUTE) },
                onNavigateToGateways = { navController.navigate("gateways") },
                onNavigateToEditServer = { server ->
                    when (server) {
                        is LaunchableTarget.GatewayAgent ->
                            navController.navigate("gateway_agents/${server.gatewaySource.id}")
                        is LaunchableTarget.Manual ->
                            navController.navigate("edit_server/${server.id}")
                    }
                },
                // Drilling an agent fills the middle pane instead of pushing a route; the
                // workspace owns the spine.
                onNavigateToSessions = { serverId, _, _, _ -> workspace.selectAgent(serverId) },
                onResumeSession = { session ->
                    workspace.resumeSession(
                        WorkspaceSelection(
                            serverId = session.serverId,
                            sessionId = session.sessionId,
                            cwd = session.cwd ?: "/",
                            title = session.title.orEmpty(),
                        ),
                    )
                },
                viewModel = viewModel,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                onHomeScreenReady = onHomeScreenReady,
            )
        },
        sessionsPane = { serverId, showBackButton ->
            WorkspaceSessionsPane(
                serverId = serverId,
                workspace = workspace,
                showBackButton = showBackButton,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        },
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.gatewaysDestination(navController: NavHostController) {
    composable("gateways") {
        val viewModel: GatewayListViewModel = hiltViewModel()
        GatewayListScreen(
            onNavigateBack = { navController.popBackStack() },
            onPairAnother = { navController.navigate(ADD_GATEWAY_ROUTE) },
            onEditGateway = { gateway -> navController.navigate("edit_gateway/${gateway.id}") },
            onOpenGatewayAgents = { gatewayId -> navController.navigate("gateway_agents/$gatewayId") },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.addServerDestination(navController: NavHostController) {
    composable(
        route = "add_server?name={name}&scheme={scheme}&host={host}",
        arguments =
            listOf(
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("scheme") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("host") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) {
        LocalNetworkPermissionGate {
            val viewModel: AddServerViewModel = hiltViewModel()
            AddServerScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.addGatewayDestination(navController: NavHostController) {
    composable(ADD_GATEWAY_ROUTE) {
        LocalNetworkPermissionGate {
            val viewModel: AddGatewayViewModel = hiltViewModel()
            AddGatewayScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.editGatewayDestination(navController: NavHostController) {
    composable(
        route = "edit_gateway/{serverId}",
        arguments = listOf(navArgument(SERVER_ID_ARG) { type = NavType.StringType }),
    ) {
        val viewModel: AddGatewayViewModel = hiltViewModel()
        AddGatewayScreen(
            onNavigateBack = { navController.popBackStack() },
            viewModel = viewModel,
        )
    }
}

/**
 * Nav result key: the custom-agent form reports a successful create to the gateway-agents
 * screen, which re-reads the gateway's agent list so the new agent is actually visible.
 */
private const val CUSTOM_AGENT_CREATED_RESULT = "customAgentCreated"

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.addCustomAgentDestination(navController: NavHostController) {
    composable(
        route = "add_custom_agent/{serverId}",
        arguments = listOf(navArgument(SERVER_ID_ARG) { type = NavType.StringType }),
    ) {
        val viewModel: AddCustomAgentViewModel = hiltViewModel()
        AddCustomAgentScreen(
            onNavigateBack = { navController.popBackStack() },
            onCreated = {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(CUSTOM_AGENT_CREATED_RESULT, true)
                navController.popBackStack()
            },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.gatewayAgentsDestination(navController: NavHostController) {
    composable(
        route = "gateway_agents/{serverId}",
        arguments = listOf(navArgument(SERVER_ID_ARG) { type = NavType.StringType }),
    ) { entry ->
        val viewModel: GatewayAgentsViewModel = hiltViewModel()
        val createdState = entry.savedStateHandle.getStateFlow(CUSTOM_AGENT_CREATED_RESULT, false)
        val customAgentCreated by createdState.collectAsStateWithLifecycle()
        LaunchedEffect(customAgentCreated) {
            if (customAgentCreated) {
                entry.savedStateHandle[CUSTOM_AGENT_CREATED_RESULT] = false
                viewModel.refresh()
            }
        }
        GatewayAgentsScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToAddCustomAgent = { navController.navigate("add_custom_agent/${viewModel.gatewayId}") },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.editServerDestination(navController: NavHostController) {
    composable(
        route = "edit_server/{serverId}",
        arguments = listOf(navArgument(SERVER_ID_ARG) { type = NavType.StringType }),
    ) {
        val viewModel: AddServerViewModel = hiltViewModel()
        AddServerScreen(
            onNavigateBack = { navController.popBackStack() },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.sessionsDestination(
    navController: NavHostController,
    sharedTransitionLayout: SharedTransitionScope,
) {
    composable(
        route = "sessions/{serverId}?create={create}&name={name}",
        arguments =
            listOf(
                navArgument(SERVER_ID_ARG) { type = NavType.StringType },
                navArgument("create") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        val serverId = backStackEntry.arguments?.getString(SERVER_ID_ARG) ?: return@composable
        val serverNameArg = backStackEntry.arguments?.getString("name")
        val openCreateSessionDialog = backStackEntry.arguments?.getBoolean("create") == true
        val viewModel: SessionListViewModel = hiltViewModel()

        val server by viewModel.server.collectAsState()
        SessionListScreen(
            navArgName = serverNameArg,
            loadedName = server?.name,
            serverId = serverId,
            openCreateSessionDialogOnLaunch = openCreateSessionDialog,
            onNavigateBack = { navController.popBackStack() },
            onNavigateToChat = { sessionId, cwd, updatedAt, title ->
                val encodedCwd = Uri.encode(cwd)
                val encodedTitle = Uri.encode(title.orEmpty())
                val updatedAtParam = updatedAt ?: -1L
                navController.navigate(
                    "chat/$serverId/$sessionId?cwd=$encodedCwd&updatedAt=$updatedAtParam&title=$encodedTitle",
                )
            },
            viewModel = viewModel,
            sharedTransitionScope = sharedTransitionLayout,
            animatedContentScope = this,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.chatDestination(
    navController: NavHostController,
    sharedTransitionLayout: SharedTransitionScope,
) {
    composable(
        route =
            "chat/{serverId}/{sessionId}?cwd={cwd}&updatedAt={updatedAt}&title={title}" +
                "&gatewayId={gatewayId}&slide={slide}&travel={travel}",
        arguments =
            listOf(
                navArgument(SERVER_ID_ARG) { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("cwd") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "/"
                },
                navArgument("updatedAt") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("gatewayId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("slide") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("travel") {
                    type = NavType.FloatType
                    defaultValue = 0f
                },
            ),
    ) { backStackEntry ->
        val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
        // The route carries the session's real title, which the view model reads from the
        // nav args and then keeps in sync with the session store; the placeholder is
        // display-only and only covers the frame before the first snapshot lands.
        val fallbackTitle = stringResource(R.string.app_untitled_session)
        ChatScreen(
            sessionId = sessionId,
            fallbackTitle = fallbackTitle,
            onNavigateBack = { navController.popBackStack() },
            onSwitchSession = { session, slide ->
                navController.switchToChat(session, slide)
            },
            sharedTransitionScope = sharedTransitionLayout,
            animatedContentScope = this,
        )
    }
}
