package com.tamimarafat.ferngeist

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tamimarafat.ferngeist.ui.theme.FerngeistTheme
import com.tamimarafat.ferngeist.feature.serverlist.AddServerViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ServerListViewModel
import com.tamimarafat.ferngeist.feature.serverlist.ui.AddServerScreen
import com.tamimarafat.ferngeist.feature.serverlist.ui.ServerListScreen
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionListScreen
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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

@Composable
fun FerngeistNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "server_list",
    ) {
        composable("server_list") {
            val viewModel: ServerListViewModel = hiltViewModel()
            ServerListScreen(
                onNavigateToAddServer = { navController.navigate("add_server") },
                onNavigateToEditServer = { serverId -> navController.navigate("edit_server/$serverId") },
                onNavigateToSessions = { serverId, _ ->
                    navController.navigate("sessions/$serverId")
                },
                viewModel = viewModel,
            )
        }

        composable("add_server") {
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
            route = "sessions/{serverId}",
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId") ?: return@composable
            val viewModel: SessionListViewModel = hiltViewModel()

            val server by viewModel.server.collectAsState()
            SessionListScreen(
                serverId = serverId,
                serverName = server?.name ?: "Sessions",
                onNavigateBack = { navController.popBackStack() },
                onNavigateToChat = { sessionId, cwd, updatedAt, title ->
                    val encodedCwd = Uri.encode(cwd)
                    val encodedTitle = Uri.encode(title ?: "Untitled Session")
                    val updatedAtParam = updatedAt ?: -1L
                    navController.navigate("chat/$serverId/$sessionId?cwd=$encodedCwd&updatedAt=$updatedAtParam&title=$encodedTitle")
                },
                viewModel = viewModel,
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
            val title = Uri.decode(backStackEntry.arguments?.getString("title") ?: "Untitled Session")
            ChatScreen(
                sessionTitle = title,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
