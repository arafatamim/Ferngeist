package com.tamimarafat.ferngeist

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.platform.LocalContext
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
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreen
import com.tamimarafat.ferngeist.feature.serverlist.AddServerViewModel
import com.tamimarafat.ferngeist.feature.serverlist.AddDesktopHelperViewModel
import com.tamimarafat.ferngeist.feature.serverlist.DesktopCompanionListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.DesktopHelperAgentsViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddDesktopHelperScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddServerScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.DesktopCompanionListScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.DesktopHelperAgentsScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.ServerListScreen
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionListScreen
import com.tamimarafat.ferngeist.service.BatteryOptimizationDialog
import com.tamimarafat.ferngeist.service.BatteryOptimizationHelper
import com.tamimarafat.ferngeist.service.BatteryOptimizationPreferences
import com.tamimarafat.ferngeist.ui.theme.FerngeistTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FerngeistTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FerngeistNavHost()
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FerngeistNavHost() {
    val navController = rememberNavController()
    val navSpring = spring<IntOffset>()
    val navFadeSpring = spring<Float>()

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = "server_list",
            enterTransition = {
                if (isSessionChatTransition()) {
                    EnterTransition.None
                } else {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = navSpring,
                    ) + fadeIn(animationSpec = navFadeSpring)
                }
            },
            exitTransition = {
                if (isSessionChatTransition()) {
                    ExitTransition.None
                } else {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = navSpring,
                    ) + fadeOut(animationSpec = navFadeSpring)
                }
            },
            popEnterTransition = {
                if (isSessionChatTransition()) {
                    EnterTransition.None
                } else {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = navSpring,
                    ) + fadeIn(animationSpec = navFadeSpring)
                }
            },
            popExitTransition = {
                if (isSessionChatTransition()) {
                    ExitTransition.None
                } else {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = navSpring,
                    ) + fadeOut(animationSpec = navFadeSpring)
                }
            },
        ) {
            composable("server_list") {
                val viewModel: ServerListViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsState()
                val context = LocalContext.current
                val batteryPrefs = remember(context) { BatteryOptimizationPreferences(context) }
                val isDismissed by batteryPrefs.isDismissed.collectAsState()
                var showBatteryDialog by remember { mutableStateOf(false) }

                NotificationPermissionEffect()

                LaunchedEffect(uiState.connectionState, isDismissed) {
                    val shouldShow = uiState.connectionState is AcpConnectionState.Connected &&
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

                ServerListScreen(
                    onNavigateToAddServer = { navController.navigate("add_server") },
                    onNavigateToPairDesktopCompanion = { navController.navigate("add_desktop_helper") },
                    onNavigateToDesktopCompanions = { navController.navigate("desktop_companions") },
                    onNavigateToEditServer = { server ->
                        when (server) {
                            is LaunchableTarget.HelperAgent -> navController.navigate("desktop_companion_agents/${server.helperSource.id}")
                            is LaunchableTarget.Manual -> navController.navigate("edit_server/${server.id}")
                        }
                    },
                    onNavigateToSessions = { serverId, _, openCreateSessionDialog ->
                        navController.navigate("sessions/$serverId?create=$openCreateSessionDialog")
                    },
                    viewModel = viewModel,
                )
            }

            composable("desktop_companions") {
                val viewModel: DesktopCompanionListViewModel = hiltViewModel()
                DesktopCompanionListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onPairAnother = { navController.navigate("add_desktop_helper") },
                    onEditCompanion = { companion -> navController.navigate("edit_desktop_helper/${companion.id}") },
                    onOpenCompanionAgents = { companionId -> navController.navigate("desktop_companion_agents/$companionId") },
                    viewModel = viewModel,
                )
            }

            composable(
                route = "add_server?name={name}&scheme={scheme}&host={host}",
                arguments = listOf(
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

            composable("add_desktop_helper") {
                val viewModel: AddDesktopHelperViewModel = hiltViewModel()
                AddDesktopHelperScreen(
                    onNavigateBack = { navController.popBackStack() },
                    viewModel = viewModel,
                )
            }

            composable(
                route = "edit_desktop_helper/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
            ) {
                val viewModel: AddDesktopHelperViewModel = hiltViewModel()
                AddDesktopHelperScreen(
                    onNavigateBack = { navController.popBackStack() },
                    viewModel = viewModel,
                )
            }

            composable(
                route = "desktop_companion_agents/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
            ) {
                val viewModel: DesktopHelperAgentsViewModel = hiltViewModel()
                DesktopHelperAgentsScreen(
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
                route = "sessions/{serverId}?create={create}",
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("create") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getString("serverId") ?: return@composable
                val openCreateSessionDialog = backStackEntry.arguments?.getBoolean("create") == true
                val viewModel: SessionListViewModel = hiltViewModel()

                val server by viewModel.server.collectAsState()
                SessionListScreen(
                    serverName = server?.name ?: "Sessions",
                    openCreateSessionDialogOnLaunch = openCreateSessionDialog,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToChat = { sessionId, cwd, updatedAt, title ->
                        val encodedCwd = Uri.encode(cwd)
                        val encodedTitle = Uri.encode(title ?: "Untitled Session")
                        val updatedAtParam = updatedAt ?: -1L
                        navController.navigate("chat/$serverId/$sessionId?cwd=$encodedCwd&updatedAt=$updatedAtParam&title=$encodedTitle")
                    },
                    viewModel = viewModel,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this@composable,
                )
            }

            composable(
                route = "chat/{serverId}/{sessionId}?cwd={cwd}&updatedAt={updatedAt}&title={title}",
                arguments = listOf(
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
                val title = Uri.decode(backStackEntry.arguments?.getString("title") ?: "Untitled Session")
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

@Composable
private fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val context = LocalContext.current
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val hasPermission = remember {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) {
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) {
                launcher.launch(permission)
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isSessionChatTransition(): Boolean {
    val fromRoute = initialState.destination.route ?: return false
    val toRoute = targetState.destination.route ?: return false
    return (fromRoute.startsWith("sessions/") && toRoute.startsWith("chat/")) ||
        (fromRoute.startsWith("chat/") && toRoute.startsWith("sessions/"))
}