package com.tamimarafat.ferngeist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.common.ui.isWindowCompact
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
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
import com.tamimarafat.ferngeist.push.resolveChatDeepLink
import com.tamimarafat.ferngeist.service.BatteryOptimizationDialog
import com.tamimarafat.ferngeist.service.BatteryOptimizationHelper
import com.tamimarafat.ferngeist.service.BatteryOptimizationPreferences
import com.tamimarafat.ferngeist.ui.theme.FerngeistTheme
import com.tamimarafat.ferngeist.workspace.ChatViewModelFactory
import com.tamimarafat.ferngeist.workspace.SERVER_LIST_ROUTE
import com.tamimarafat.ferngeist.workspace.WorkspaceRouteAction
import com.tamimarafat.ferngeist.workspace.WorkspaceScreen
import com.tamimarafat.ferngeist.workspace.WorkspaceSelection
import com.tamimarafat.ferngeist.workspace.WorkspaceSessionsPane
import com.tamimarafat.ferngeist.workspace.WorkspaceState
import com.tamimarafat.ferngeist.workspace.compactChatRoute
import com.tamimarafat.ferngeist.workspace.rememberWorkspaceState
import com.tamimarafat.ferngeist.workspace.sessionsRouteFor
import com.tamimarafat.ferngeist.workspace.workspaceRouteAction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The single-activity entry point for Ferngeist.
 *
 * Configures edge-to-edge rendering, installs the splash screen, and hosts
 * [FerngeistNavHost] inside the app theme.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Translates a push's gateway-owned server id to the local GatewaySource id when a
    // system-displayed notification (app killed/background) is tapped; see FerngeistNavHost.
    @Inject
    lateinit var gatewaySourceRepository: GatewaySourceRepository

    @Inject
    lateinit var chatConnectionHub: ChatConnectionHub

    // Builds a pane-hosted ChatViewModel: panes are not nav destinations, so the wide
    // workspace cannot reach one through hiltViewModel().
    @Inject
    lateinit var chatViewModelFactory: ChatViewModelFactory

    // Latest launch/notification intent, exposed to the nav host so a notification
    // tap can deep-link to the active chat on both cold start and warm resume.
    private val latestIntent = MutableStateFlow<Intent?>(null)

    // Splash stays up until the home screen has its data and has drawn it. Without
    // this the system splash exits on the first frame, which on a cold start lands
    // before the agent list has anything to show.
    private val homeScreenReady = MutableStateFlow(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntent.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // Hold the splash on a cold start until the agent list has data and has drawn it; without
        // a condition the system splash exits on the first frame, which lands before the list has
        // anything to show. Only a cold start lands on `server_list`, the screen that flips
        // `homeScreenReady`. A config-change relaunch restores whatever was on top — chat included
        // — never composes `server_list`, and would hold the splash forever with the window
        // unshown: a black screen with no focused window, then an ANR. Nothing is lost on a
        // relaunch, because the list ViewModel survives it and draws populated either way.
        val coldStart = savedInstanceState == null
        splashScreen.setKeepOnScreenCondition { coldStart && !homeScreenReady.value }
        super.onCreate(savedInstanceState)
        latestIntent.value = intent
        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    lightScrim = Color.Transparent.toArgb(),
                    darkScrim = Color.Transparent.toArgb(),
                ),
            navigationBarStyle =
                SystemBarStyle.auto(
                    lightScrim = Color.Transparent.toArgb(),
                    darkScrim = Color.Transparent.toArgb(),
                ),
        )
        setContent {
            FerngeistTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FerngeistNavHost(
                        latestIntent = latestIntent,
                        onIntentConsumed = { latestIntent.value = null },
                        translateGatewayId = { gatewayId ->
                            gatewaySourceRepository.getGatewayByGatewayId(gatewayId)?.id
                        },
                        chatConnectionHub = chatConnectionHub,
                        chatViewModelFactory = chatViewModelFactory,
                        onHomeScreenReady = { homeScreenReady.value = it },
                    )
                }
            }
        }
    }
}

/**
 * Top-level navigation host composable.
 *
 * Manages the [NavHost] for all app screens and renders the
 * [BatteryOptimizationDialog] above the navigation layer so it persists
 * across destination changes. The dialog appears when the ACP connection is
 * established and battery optimisations have not been disabled (unless
 * previously dismissed).
 *
 * Also wires shared-element transition specs (spring-based slide + fade)
 * and suppresses transitions for session↔chat navigation via
 * [isSessionChatTransition].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FerngeistNavHost(
    latestIntent: StateFlow<Intent?> = MutableStateFlow(null),
    onIntentConsumed: () -> Unit = {},
    translateGatewayId: suspend (String) -> String? = { null },
    chatConnectionHub: ChatConnectionHub,
    chatViewModelFactory: ChatViewModelFactory,
    onHomeScreenReady: (Boolean) -> Unit = {},
) {
    val navController = rememberNavController()
    val navSpring = spring<IntOffset>()
    val navFadeSpring = spring<Float>()

    val context = LocalContext.current
    BatteryOptimizationGate(chatConnectionHub, context)

    // Width picks the presentation INSIDE the `server_list` destination, never the graph's
    // shape. The window can change under a live back stack — fold, unfold, split-screen
    // resize — and `NavHost` answers that by re-running `setGraph`; a graph that gained or
    // lost a destination cannot restore that stack and throws. One graph, one start
    // destination, one route set, at every width.
    val workspace = rememberWorkspaceState()
    val compact = isWindowCompact()

    // A chat notification selects the pinned pane on a wide window instead of pushing the
    // full-screen chat route over the workspace; compact windows have no workspace to select.
    DeepLinkEffect(
        navController,
        latestIntent,
        translateGatewayId,
        onIntentConsumed,
        if (compact) null else workspace,
    )

    // The workspace holds its selection in saveable state, not on the back stack, so a window
    // class change has to convert between the two representations: folding with a chat open used
    // to land on the agent list, and unfolding used to leave the full-screen chat route covering
    // the workspace it should be a pane of. Keying on the entry is also what makes this wait for
    // `NavHost` to install the graph; neither branch suspends, so a navigation it starts cannot
    // be cancelled half-done by the key change that navigation causes.
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(compact, currentEntry) {
        val entry = currentEntry ?: return@LaunchedEffect
        val action =
            workspaceRouteAction(
                compact = compact,
                route = entry.destination.route,
                selectedServerId = workspace.selectedServerId,
                selectedSessionId = workspace.selectedSessionId,
            )
        when (action) {
            WorkspaceRouteAction.None -> Unit

            WorkspaceRouteAction.AbsorbIntoWorkspace -> {
                val args = entry.arguments ?: return@LaunchedEffect
                val serverId = args.getString("serverId") ?: return@LaunchedEffect
                val sessionId = args.getString("sessionId")
                if (sessionId == null) {
                    workspace.selectAgent(serverId)
                } else {
                    workspace.selectSession(
                        WorkspaceSelection(
                            serverId = serverId,
                            sessionId = sessionId,
                            cwd = Uri.decode(args.getString("cwd").orEmpty()).orEmpty().ifEmpty { "/" },
                            title = Uri.decode(args.getString("title").orEmpty()).orEmpty(),
                        ),
                    )
                }
                navController.popBackStack(SERVER_LIST_ROUTE, inclusive = false)
            }

            is WorkspaceRouteAction.RestoreCompact -> {
                val cwd = workspace.cwd
                val title = workspace.title
                // A create-on-arrival chat's selection still holds the placeholder id; the real
                // one it minted is the only id the compact route can reopen.
                val sessionId = workspace.mintedSessionId ?: action.sessionId
                workspace.clearSelection()
                // The compact flow's own stack, so back from the chat reaches the session list
                // exactly as it would had the user navigated there themselves.
                navController.navigate(sessionsRouteFor(action.serverId))
                if (sessionId != null) {
                    navController.navigate(
                        compactChatRoute(
                            serverId = action.serverId,
                            sessionId = sessionId,
                            encodedCwd = Uri.encode(cwd),
                            encodedTitle = Uri.encode(title),
                        ),
                    )
                }
            }
        }
    }

    // The compact sessions screen only drifts a short distance while it cross-fades; a full-height
    // slide reads as a page push, which this pair is not.
    val sessionListSlidePx = with(LocalDensity.current) { SESSION_LIST_SLIDE_DP.dp.roundToPx() }

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = "server_list",
            enterTransition = { navEnterTransition(navSpring, navFadeSpring, sessionListSlidePx) },
            exitTransition = { navExitTransition(navSpring, navFadeSpring, sessionListSlidePx) },
            popEnterTransition = { navPopEnterTransition(navSpring, navFadeSpring, sessionListSlidePx) },
            popExitTransition = { navPopExitTransition(navSpring, navFadeSpring, sessionListSlidePx) },
        ) {
            FerngeistDestinations(
                navController = navController,
                sharedTransitionLayout = this@SharedTransitionLayout,
                workspace = workspace,
                chatConnectionHub = chatConnectionHub,
                chatViewModelFactory = chatViewModelFactory,
                onHomeScreenReady = onHomeScreenReady,
            )
        }
    }
}

/** Registers every destination in the app. */
@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.FerngeistDestinations(
    navController: NavHostController,
    sharedTransitionLayout: SharedTransitionScope,
    workspace: WorkspaceState,
    chatConnectionHub: ChatConnectionHub,
    chatViewModelFactory: ChatViewModelFactory,
    onHomeScreenReady: (Boolean) -> Unit,
) {
    ServerListDestination(
        navController = navController,
        sharedTransitionLayout = sharedTransitionLayout,
        workspace = workspace,
        chatConnectionHub = chatConnectionHub,
        chatViewModelFactory = chatViewModelFactory,
        onHomeScreenReady = onHomeScreenReady,
    )
    GatewaysDestination(navController)
    AddServerDestination(navController)
    AddGatewayDestination(navController)
    EditGatewayDestination(navController)
    GatewayAgentsDestination(navController)
    AddCustomAgentDestination(navController)
    EditServerDestination(navController)
    SessionsDestination(navController, sharedTransitionLayout)
    ChatDestination(navController, sharedTransitionLayout)
}

/**
 * Deep-links a notification tap to the referenced chat session.
 *
 * A wide window shows chat as a pinned pane, not a screen, so the tap selects the session in
 * [workspace] instead of navigating: pushing `chat/...` there would cover the whole workspace
 * with the full-screen chat. Non-chat deep links, and every deep link on a compact window
 * (where [workspace] is null), keep navigating.
 */
@Composable
private fun DeepLinkEffect(
    navController: NavHostController,
    latestIntent: StateFlow<Intent?>,
    translateGatewayId: suspend (String) -> String?,
    onIntentConsumed: () -> Unit,
    workspace: WorkspaceState?,
) {
    // Handles both the connection/in-app notifications (our own extras) and a system-displayed
    // FCM notification tapped while the app was killed/background (raw FCM data keys).
    val pendingIntent by latestIntent.collectAsState()
    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        val target = resolveChatDeepLink(intent, translateGatewayId)
        if (target != null) {
            if (workspace != null) {
                // Nothing to do when the pinned chat is already the target: re-resuming it
                // would step the left pane back to the agent list under the user.
                val alreadySelected =
                    workspace.selectedServerId == target.serverId &&
                        workspace.selectedSessionId == target.sessionId
                if (!alreadySelected) {
                    workspace.resumeSession(
                        WorkspaceSelection(
                            serverId = target.serverId,
                            sessionId = target.sessionId,
                            cwd = target.cwd,
                            title = target.title,
                        ),
                    )
                }
            } else {
                val gatewayIdParam = target.gatewayId?.let { "&gatewayId=${Uri.encode(it)}" } ?: ""
                val titleParam =
                    if (target.title.isNotBlank()) "&title=${Uri.encode(target.title)}" else ""
                navController.navigate(
                    "chat/${target.serverId}/${target.sessionId}" +
                        "?cwd=${Uri.encode(target.cwd)}$titleParam$gatewayIdParam",
                ) {
                    launchSingleTop = true
                }
            }
        }
        onIntentConsumed()
    }
}

@Composable
private fun BatteryOptimizationGate(
    chatConnectionHub: ChatConnectionHub,
    context: Context,
) {
    val batteryPrefs = remember(context) { BatteryOptimizationPreferences(context) }
    val isDismissed by batteryPrefs.isDismissed.collectAsState(initial = false)
    var dismissLoaded by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    val anyConnected by chatConnectionHub.anyConnected.collectAsState()

    LaunchedEffect(batteryPrefs) {
        batteryPrefs.isDismissed.first()
        dismissLoaded = true
    }

    LaunchedEffect(anyConnected, isDismissed, dismissLoaded) {
        if (!dismissLoaded) return@LaunchedEffect
        val shouldShow =
            anyConnected &&
                !isDismissed &&
                !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        showBatteryDialog = shouldShow
    }

    if (showBatteryDialog) {
        BatteryOptimizationDialog(
            onDismiss = {
                showBatteryDialog = false
                batteryPrefs.markDismissed()
            },
            onBackFromSettings = {
                if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
                    batteryPrefs.markDismissed()
                }
            },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.ServerListDestination(
    navController: NavHostController,
    sharedTransitionLayout: SharedTransitionScope,
    workspace: WorkspaceState,
    chatConnectionHub: ChatConnectionHub,
    chatViewModelFactory: ChatViewModelFactory,
    onHomeScreenReady: (Boolean) -> Unit,
) {
    composable("server_list") {
        val viewModel: ServerListViewModel = hiltViewModel()

        NotificationPermissionEffect()

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
                chatConnectionHub = chatConnectionHub,
                chatViewModelFactory = chatViewModelFactory,
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
        onNavigateToPairGateway = { navController.navigate("add_gateway") },
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
            val encodedCwd = Uri.encode(session.cwd ?: "")
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
                onNavigateToPairGateway = { navController.navigate("add_gateway") },
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
private fun NavGraphBuilder.GatewaysDestination(navController: NavHostController) {
    composable("gateways") {
        val viewModel: GatewayListViewModel = hiltViewModel()
        GatewayListScreen(
            onNavigateBack = { navController.popBackStack() },
            onPairAnother = { navController.navigate("add_gateway") },
            onEditGateway = { gateway -> navController.navigate("edit_gateway/${gateway.id}") },
            onOpenGatewayAgents = { gatewayId -> navController.navigate("gateway_agents/$gatewayId") },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.AddServerDestination(navController: NavHostController) {
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
        val viewModel: AddServerViewModel = hiltViewModel()
        AddServerScreen(
            onNavigateBack = { navController.popBackStack() },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.AddGatewayDestination(navController: NavHostController) {
    composable("add_gateway") {
        val viewModel: AddGatewayViewModel = hiltViewModel()
        AddGatewayScreen(
            onNavigateBack = { navController.popBackStack() },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.EditGatewayDestination(navController: NavHostController) {
    composable(
        route = "edit_gateway/{serverId}",
        arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
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
private fun NavGraphBuilder.AddCustomAgentDestination(navController: NavHostController) {
    composable(
        route = "add_custom_agent/{serverId}",
        arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
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
private fun NavGraphBuilder.GatewayAgentsDestination(navController: NavHostController) {
    composable(
        route = "gateway_agents/{serverId}",
        arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
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
private fun NavGraphBuilder.EditServerDestination(navController: NavHostController) {
    composable(
        route = "edit_server/{serverId}",
        arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
    ) {
        val viewModel: AddServerViewModel = hiltViewModel()
        AddServerScreen(
            onNavigateBack = { navController.popBackStack() },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.SessionsDestination(
    navController: NavHostController,
    sharedTransitionLayout: SharedTransitionScope,
) {
    composable(
        route = "sessions/{serverId}?create={create}&name={name}",
        arguments =
            listOf(
                navArgument("serverId") { type = NavType.StringType },
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
        val serverId = backStackEntry.arguments?.getString("serverId") ?: return@composable
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
private fun NavGraphBuilder.ChatDestination(
    navController: NavHostController,
    sharedTransitionLayout: SharedTransitionScope,
) {
    composable(
        route =
            "chat/{serverId}/{sessionId}?cwd={cwd}&updatedAt={updatedAt}&title={title}" +
                "&gatewayId={gatewayId}&slide={slide}",
        arguments =
            listOf(
                navArgument("serverId") { type = NavType.StringType },
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
            onSwitchSession = { session, slideDirection ->
                navController.switchToChat(session, slideDirection)
            },
            sharedTransitionScope = sharedTransitionLayout,
            animatedContentScope = this,
        )
    }
}

/**
 * Requests the `POST_NOTIFICATIONS` permission (Android 13+) on first composition
 * if it has not been granted yet. Runs once via [LaunchedEffect].
 */
@Composable
private fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val context = LocalContext.current
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val hasPermission =
            remember {
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        if (!hasPermission) {
            val launcher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { }
            LaunchedEffect(Unit) {
                launcher.launch(permission)
            }
        }
    }
}

/** How far the compact sessions screen drifts while it cross-fades, in dp. */
private const val SESSION_LIST_SLIDE_DP = 40

/**
 * Returns `true` when navigating between `sessions/{id}` and `chat/{id}`.
 *
 * Used to suppress the default slide/fade animation so the shared-element
 * transition defined inside the two screens drives the visual change instead.
 */

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnterTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
    sessionListSlidePx: Int,
): EnterTransition =
    when {
        isSessionChatTransition() -> EnterTransition.None
        isServerListSessionsTransition() ->
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Up,
                animationSpec = navSpring,
                initialOffset = { sessionListSlidePx },
            ) + fadeIn(animationSpec = navFadeSpring)
        isChatChatTransition() ->
            slideIntoContainer(
                towards = chatChatDirection(),
                animationSpec = navSpring,
            ) + fadeIn(animationSpec = navFadeSpring)
        else ->
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = navSpring,
            ) + fadeIn(animationSpec = navFadeSpring)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navExitTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
    sessionListSlidePx: Int,
): ExitTransition =
    when {
        isSessionChatTransition() -> ExitTransition.None
        isServerListSessionsTransition() ->
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Up,
                animationSpec = navSpring,
                targetOffset = { sessionListSlidePx },
            ) + fadeOut(animationSpec = navFadeSpring)
        isChatChatTransition() ->
            slideOutOfContainer(
                towards = chatChatDirection(),
                animationSpec = navSpring,
            ) + fadeOut(animationSpec = navFadeSpring)
        else ->
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = navSpring,
            ) + fadeOut(animationSpec = navFadeSpring)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopEnterTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
    sessionListSlidePx: Int,
): EnterTransition =
    when {
        isSessionChatTransition() -> EnterTransition.None
        isServerListSessionsTransition() ->
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Down,
                animationSpec = navSpring,
                initialOffset = { sessionListSlidePx },
            ) + fadeIn(animationSpec = navFadeSpring)
        else ->
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = navSpring,
            ) + fadeIn(animationSpec = navFadeSpring)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExitTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
    sessionListSlidePx: Int,
): ExitTransition =
    when {
        isSessionChatTransition() -> ExitTransition.None
        isServerListSessionsTransition() ->
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Down,
                animationSpec = navSpring,
                targetOffset = { sessionListSlidePx },
            ) + fadeOut(animationSpec = navFadeSpring)
        else ->
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = navSpring,
            ) + fadeOut(animationSpec = navFadeSpring)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isSessionChatTransition(): Boolean {
    val fromRoute = initialState.destination.route ?: return false
    val toRoute = targetState.destination.route ?: return false
    return (fromRoute.startsWith("sessions/") && toRoute.startsWith("chat/")) ||
        (fromRoute.startsWith("chat/") && toRoute.startsWith("sessions/"))
}

/**
 * Returns `true` when navigating between `server_list` and `sessions/{id}`.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isServerListSessionsTransition(): Boolean {
    val fromRoute = initialState.destination.route ?: return false
    val toRoute = targetState.destination.route ?: return false
    return (fromRoute == "server_list" && toRoute.startsWith("sessions/")) ||
        (fromRoute.startsWith("sessions/") && toRoute == "server_list")
}
