package com.tamimarafat.ferngeist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import kotlinx.coroutines.flow.first
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreen
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.AddServerViewModel
import com.tamimarafat.ferngeist.feature.serverlist.GatewayAgentsViewModel
import com.tamimarafat.ferngeist.feature.serverlist.GatewayListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddGatewayScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddPaseoScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddServerScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.GatewayAgentsScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.GatewayListScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.ServerListScreen
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionListScreen
import com.tamimarafat.ferngeist.service.BatteryOptimizationDialog
import com.tamimarafat.ferngeist.service.BatteryOptimizationHelper
import com.tamimarafat.ferngeist.service.BatteryOptimizationPreferences
import com.tamimarafat.ferngeist.service.FerngeistForegroundService
import com.tamimarafat.ferngeist.ui.theme.FerngeistTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single-activity entry point for Ferngeist.
 *
 * Configures edge-to-edge rendering, installs the splash screen, and hosts
 * [FerngeistNavHost] inside the app theme.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
) {
    val navController = rememberNavController()
    val navSpring = spring<IntOffset>()
    val navFadeSpring = spring<Float>()

    // Deep-link a connection-notification tap to the active chat session.
    val pendingIntent by latestIntent.collectAsState()
    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        val serverId = intent.getStringExtra(FerngeistForegroundService.EXTRA_SERVER_ID)
        val sessionId = intent.getStringExtra(FerngeistForegroundService.EXTRA_SESSION_ID)
        if (serverId != null && sessionId != null) {
            val cwd = intent.getStringExtra(FerngeistForegroundService.EXTRA_CWD) ?: "/"
            val title = intent.getStringExtra(FerngeistForegroundService.EXTRA_TITLE).orEmpty()
            navController.navigate(
                "chat/$serverId/$sessionId?cwd=${Uri.encode(cwd)}&updatedAt=-1&title=${Uri.encode(title)}",
            ) {
                launchSingleTop = true
            }
        }
        onIntentConsumed()
    }

    val context = LocalContext.current
    val batteryPrefs = remember(context) { BatteryOptimizationPreferences(context) }
    val isDismissed by batteryPrefs.isDismissed.collectAsState(initial = false)
    var dismissLoaded by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    val connectionManager = (context.applicationContext as FerngeistApplication).connectionManager
    val connectionState by connectionManager.connectionState.collectAsState()

    LaunchedEffect(batteryPrefs) {
        batteryPrefs.isDismissed.first()
        dismissLoaded = true
    }

    LaunchedEffect(connectionState, isDismissed, dismissLoaded) {
        if (!dismissLoaded) return@LaunchedEffect
        val shouldShow =
            connectionState is AcpConnectionState.Connected &&
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

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = "server_list",
            enterTransition = {
                when {
                    isSessionChatTransition() -> EnterTransition.None
                    isServerListSessionsTransition() -> fadeIn(animationSpec = navFadeSpring)
                    else ->
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = navSpring,
                        ) + fadeIn(animationSpec = navFadeSpring)
                }
            },
            exitTransition = {
                when {
                    isSessionChatTransition() -> ExitTransition.None
                    isServerListSessionsTransition() -> fadeOut(animationSpec = navFadeSpring)
                    else ->
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = navSpring,
                        ) + fadeOut(animationSpec = navFadeSpring)
                }
            },
            popEnterTransition = {
                when {
                    isSessionChatTransition() -> EnterTransition.None
                    isServerListSessionsTransition() -> fadeIn(animationSpec = navFadeSpring)
                    else ->
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = navSpring,
                        ) + fadeIn(animationSpec = navFadeSpring)
                }
            },
            popExitTransition = {
                when {
                    isSessionChatTransition() -> ExitTransition.None
                    isServerListSessionsTransition() -> fadeOut(animationSpec = navFadeSpring)
                    else ->
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = navSpring,
                        ) + fadeOut(animationSpec = navFadeSpring)
                }
            },
        ) {
            composable("server_list") {
                val viewModel: ServerListViewModel = hiltViewModel()

                NotificationPermissionEffect()

                ServerListScreen(
                    onNavigateToAddServer = { navController.navigate("add_server") },
                    onNavigateToPairGateway = { navController.navigate("add_gateway") },
                    onNavigateToPairPaseo = { navController.navigate("add_paseo") },
                    onNavigateToGateways = { navController.navigate("gateways") },
                    onNavigateToEditServer = { server ->
                        when (server) {
                            is LaunchableTarget.GatewayAgent ->
                                navController.navigate(
                                    "gateway_agents/${server.gatewaySource.id}",
                                )
                            is LaunchableTarget.Manual -> navController.navigate("edit_server/${server.id}")
                            // Paseo targets have no dedicated edit screen yet.
                            is LaunchableTarget.Paseo -> Unit
                        }
                    },
                    onNavigateToSessions = { serverId, serverName, _, openCreateSessionDialog ->
                        val encodedName = Uri.encode(serverName)
                        navController.navigate(
                            "sessions/$serverId?create=$openCreateSessionDialog&name=$encodedName",
                        )
                    },
                    viewModel = viewModel,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this@composable,
                )
            }

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

            composable("add_gateway") {
                val viewModel: AddGatewayViewModel = hiltViewModel()
                AddGatewayScreen(
                    onNavigateBack = { navController.popBackStack() },
                    viewModel = viewModel,
                )
            }

            composable("add_paseo") {
                AddPaseoScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

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
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this@composable,
                )
            }

            composable(
                route = "chat/{serverId}/{sessionId}?cwd={cwd}&updatedAt={updatedAt}&title={title}",
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
                    ),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                val title = Uri.decode(backStackEntry.arguments?.getString("title") ?: stringResource(R.string.app_untitled_session))
                ChatScreen(
                    sessionId = sessionId,
                    sessionTitle = title,
                    onNavigateBack = { navController.popBackStack() },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this@composable,
                )
            }
        }
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
