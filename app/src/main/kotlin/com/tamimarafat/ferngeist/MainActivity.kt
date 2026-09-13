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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.common.ui.isWindowCompact
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreen
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.AddServerViewModel
import com.tamimarafat.ferngeist.feature.serverlist.GatewayAgentsViewModel
import com.tamimarafat.ferngeist.feature.serverlist.GatewayListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel
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
import com.tamimarafat.ferngeist.workspace.WorkspaceScreen
import com.tamimarafat.ferngeist.workspace.WorkspaceSelection
import com.tamimarafat.ferngeist.workspace.WorkspaceSessionsPane
import com.tamimarafat.ferngeist.workspace.WorkspaceState
import com.tamimarafat.ferngeist.workspace.rememberWorkspaceState
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntent.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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

    // A chat notification selects the pinned pane on a wide window instead of pushing the
    // full-screen chat route over the workspace; compact windows have no workspace to select.
    DeepLinkEffect(
        navController,
        latestIntent,
        translateGatewayId,
        onIntentConsumed,
        if (isWindowCompact()) null else workspace,
    )

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = "server_list",
            enterTransition = { navEnterTransition(navSpring, navFadeSpring) },
            exitTransition = { navExitTransition(navSpring, navFadeSpring) },
            popEnterTransition = { navPopEnterTransition(navSpring, navFadeSpring) },
            popExitTransition = { navPopExitTransition(navSpring, navFadeSpring) },
        ) {
            FerngeistDestinations(
                navController = navController,
                sharedTransitionLayout = this@SharedTransitionLayout,
                workspace = workspace,
                chatConnectionHub = chatConnectionHub,
                chatViewModelFactory = chatViewModelFactory,
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
) {
    ServerListDestination(
        navController = navController,
        sharedTransitionLayout = sharedTransitionLayout,
        workspace = workspace,
        chatConnectionHub = chatConnectionHub,
        chatViewModelFactory = chatViewModelFactory,
    )
    GatewaysDestination(navController)
    AddServerDestination(navController)
    AddGatewayDestination(navController)
    EditGatewayDestination(navController)
    GatewayAgentsDestination(navController)
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
            val encodedTitle = Uri.encode(session.title)
            navController.navigate(
                "chat/${session.serverId}/${session.sessionId}?cwd=$encodedCwd&title=$encodedTitle",
            )
        },
        viewModel = viewModel,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
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
                            title = session.title,
                        ),
                    )
                },
                viewModel = viewModel,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
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

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.GatewayAgentsDestination(navController: NavHostController) {
    composable(
        route = "gateway_agents/{serverId}",
        arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
    ) {
        val viewModel: GatewayAgentsViewModel = hiltViewModel()
        GatewayAgentsScreen(
            onNavigateBack = { navController.popBackStack() },
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
        val fallbackSessionTitle = stringResource(R.string.app_untitled_session)

        val server by viewModel.server.collectAsState()
        SessionListScreen(
            navArgName = serverNameArg,
            loadedName = server?.name,
            serverId = serverId,
            openCreateSessionDialogOnLaunch = openCreateSessionDialog,
            onNavigateBack = { navController.popBackStack() },
            onNavigateToChat = { sessionId, cwd, updatedAt, title ->
                val encodedCwd = Uri.encode(cwd)
                val encodedTitle = Uri.encode(title ?: fallbackSessionTitle)
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
                "&gatewayId={gatewayId}",
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
                    defaultValue = "Untitled Session"
                },
                navArgument("gatewayId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
        val title =
            Uri.decode(
                backStackEntry.arguments?.getString("title") ?: stringResource(R.string.app_untitled_session),
            )
        ChatScreen(
            sessionId = sessionId,
            sessionTitle = title,
            onNavigateBack = { navController.popBackStack() },
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

/**
 * Returns `true` when navigating between `sessions/{id}` and `chat/{id}`.
 *
 * Used to suppress the default slide/fade animation so the shared-element
 * transition defined inside the two screens drives the visual change instead.
 */

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnterTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
): EnterTransition =
    when {
        isSessionChatTransition() -> EnterTransition.None
        isServerListSessionsTransition() -> fadeIn(animationSpec = navFadeSpring)
        else ->
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = navSpring,
            ) + fadeIn(animationSpec = navFadeSpring)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navExitTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
): ExitTransition =
    when {
        isSessionChatTransition() -> ExitTransition.None
        isServerListSessionsTransition() -> fadeOut(animationSpec = navFadeSpring)
        else ->
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = navSpring,
            ) + fadeOut(animationSpec = navFadeSpring)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopEnterTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
): EnterTransition =
    when {
        isSessionChatTransition() -> EnterTransition.None
        isServerListSessionsTransition() -> fadeIn(animationSpec = navFadeSpring)
        else ->
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = navSpring,
            ) + fadeIn(animationSpec = navFadeSpring)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExitTransition(
    navSpring: SpringSpec<IntOffset>,
    navFadeSpring: SpringSpec<Float>,
): ExitTransition =
    when {
        isSessionChatTransition() -> ExitTransition.None
        isServerListSessionsTransition() -> fadeOut(animationSpec = navFadeSpring)
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
