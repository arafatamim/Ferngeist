package com.tamimarafat.ferngeist

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreen
import com.tamimarafat.ferngeist.onboarding.FirstLaunchOnboardingScreen
import com.tamimarafat.ferngeist.onboarding.OnboardingViewModel
import com.tamimarafat.ferngeist.feature.serverlist.AddServerViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddServerScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.ServerListScreen
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionListScreen
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
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                val servers by viewModel.servers.collectAsState()
                val onboardingCompleted by onboardingViewModel.isCompleted.collectAsState()
                val onboardingUiState by onboardingViewModel.uiState.collectAsState()

                if (!onboardingCompleted && servers.isEmpty()) {
                    FirstLaunchOnboardingScreen(
                        uiState = onboardingUiState,
                        onSkip = { onboardingViewModel.completeOnboarding() },
                        onStartSetup = {
                            onboardingViewModel.completeOnboarding()
                            navController.navigate(
                                buildAddServerRoute(
                                    name = onboardingUiState.selectedAgent?.name ?: "My Agent",
                                    scheme = "ws",
                                    host = "",
                                    cwd = "/",
                                )
                            )
                        },
                        onLoadRegistry = onboardingViewModel::ensureRegistryLoaded,
                        onSelectAgent = onboardingViewModel::selectAgent,
                        onSelectPlatform = onboardingViewModel::selectPlatform,
                        onUpdatePort = onboardingViewModel::updatePort,
                        onRetryLoad = onboardingViewModel::retryRegistryLoad,
                    )
                } else {
                    ServerListScreen(
                        onNavigateToAddServer = { navController.navigate("add_server") },
                        onNavigateToEditServer = { serverId -> navController.navigate("edit_server/$serverId") },
                        onNavigateToSessions = { serverId, _, openCreateSessionDialog ->
                            navController.navigate("sessions/$serverId?create=$openCreateSessionDialog")
                        },
                        viewModel = viewModel,
                    )
                }
            }

            composable(
                route = "add_server?name={name}&scheme={scheme}&host={host}&cwd={cwd}",
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
                    navArgument("cwd") {
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

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isSessionChatTransition(): Boolean {
    val fromRoute = initialState.destination.route ?: return false
    val toRoute = targetState.destination.route ?: return false
    return (fromRoute.startsWith("sessions/") && toRoute.startsWith("chat/")) ||
        (fromRoute.startsWith("chat/") && toRoute.startsWith("sessions/"))
}

private fun buildAddServerRoute(
    name: String,
    scheme: String,
    host: String,
    cwd: String,
): String {
    return "add_server?name=${Uri.encode(name)}&scheme=${Uri.encode(scheme)}&host=${Uri.encode(host)}&cwd=${Uri.encode(cwd)}"
}
