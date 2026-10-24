package com.tamimarafat.ferngeist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.common.ui.isWindowCompact
import com.tamimarafat.ferngeist.push.PushRegistrar
import com.tamimarafat.ferngeist.service.BatteryOptimizationDialog
import com.tamimarafat.ferngeist.ui.theme.FerngeistTheme
import com.tamimarafat.ferngeist.workspace.ChatViewModelFactory
import com.tamimarafat.ferngeist.workspace.SERVER_LIST_ROUTE
import com.tamimarafat.ferngeist.workspace.WorkspaceRouteAction
import com.tamimarafat.ferngeist.workspace.WorkspaceSelection
import com.tamimarafat.ferngeist.workspace.compactChatRoute
import com.tamimarafat.ferngeist.workspace.rememberWorkspaceState
import com.tamimarafat.ferngeist.workspace.sessionsRouteFor
import com.tamimarafat.ferngeist.workspace.workspaceRouteAction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject

/**
 * The single-activity entry point for Ferngeist.
 *
 * Configures edge-to-edge rendering, installs the splash screen, and hosts
 * [FerngeistNavHost] inside the app theme.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var chatConnectionHub: ChatConnectionHub

    // Builds a pane-hosted ChatViewModel: panes are not nav destinations, so the wide
    // workspace cannot reach one through hiltViewModel().
    @Inject
    lateinit var chatViewModelFactory: ChatViewModelFactory

    @Inject
    lateinit var pushRegistrar: PushRegistrar

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
        // Pick the UnifiedPush distributor that delivers pushes: one the user installed
        // (e.g. ntfy), else the embedded FCM one in the google build. Gateway subscriptions
        // wait for it. Reuses the current choice without UI once one is made.
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { ok ->
            if (ok) pushRegistrar.onDistributorReady()
        }
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
    val panes =
        remember(chatConnectionHub, chatViewModelFactory) {
            WorkspacePaneServices(chatConnectionHub, chatViewModelFactory)
        }

    // A chat notification selects the pinned pane on a wide window instead of pushing the
    // full-screen chat route over the workspace; compact windows have no workspace to select.
    DeepLinkEffect(
        navController,
        latestIntent,
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

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = "server_list",
            enterTransition = { navEnterTransition(navSpring, navFadeSpring) },
            exitTransition = { navExitTransition(navSpring, navFadeSpring) },
            popEnterTransition = { navPopEnterTransition(navSpring, navFadeSpring) },
            popExitTransition = { navPopExitTransition(navSpring, navFadeSpring) },
            // Navigation 2.10 drives back gestures through these instead of the pop pair, and its
            // default scales the leaving screen to 70%. Keep gestures on the same motion as the arrow.
            predictivePopEnterTransition = { _ -> navPopEnterTransition(navSpring, navFadeSpring) },
            predictivePopExitTransition = { _ -> navPopExitTransition(navSpring, navFadeSpring) },
        ) {
            ferngeistDestinations(
                navController = navController,
                sharedTransitionLayout = this@SharedTransitionLayout,
                workspace = workspace,
                panes = panes,
                onHomeScreenReady = onHomeScreenReady,
            )
        }
    }
}
