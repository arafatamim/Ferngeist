package com.tamimarafat.ferngeist.workspace

import android.os.Bundle
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.defaultViewModelCreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListViewModel
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionListScreen

/**
 * The workspace's sessions pane: [SessionListScreen] bound to one agent, hosted by the
 * workspace rather than registered as a route.
 *
 * [SessionListViewModel] reads its `serverId` out of the `SavedStateHandle`, which a nav
 * destination fills from its route arguments. A pane has no route, so the argument rides the
 * same channel navigation uses: `DEFAULT_ARGS_KEY` in the creation extras.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun WorkspaceSessionsPane(
    serverId: String,
    workspace: WorkspaceState,
    showBackButton: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val viewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner to scope the sessions pane to"
        }
    val extras =
        remember(viewModelStoreOwner, serverId) {
            MutableCreationExtras(viewModelStoreOwner.defaultViewModelCreationExtras).apply {
                set(DEFAULT_ARGS_KEY, Bundle().apply { putString("serverId", serverId) })
            }
        }
    val viewModel: SessionListViewModel =
        viewModel(
            viewModelStoreOwner = viewModelStoreOwner,
            key = "workspace-sessions:$serverId",
            factory = rememberHiltViewModelFactory(),
            extras = extras,
        )
    val server by viewModel.server.collectAsStateWithLifecycle()
    val fallbackSessionTitle = stringResource(R.string.app_untitled_session)

    SessionListScreen(
        navArgName = null,
        loadedName = server?.name,
        serverId = serverId,
        // The workspace owns the spine, so there is no route to pop: "back" steps the left
        // pane up to the agent list instead. That only has meaning when the agent list is
        // hidden — at three panes it is already on screen, so the button would do nothing.
        showBackButton = showBackButton,
        // The workspace can show the agent list beside this list, and both claim
        // ServerNameSharedBoundsKey for the same server — two claimants scale the title to the
        // agent card's bounds. Panes never share bounds; only the compact flow morphs.
        sharedBoundsEnabled = false,
        onNavigateBack = { workspace.backToAgents() },
        onNavigateToChat = { sessionId, cwd, _, title ->
            workspace.selectSession(
                WorkspaceSelection(
                    serverId = serverId,
                    sessionId = sessionId,
                    cwd = cwd,
                    title = title ?: fallbackSessionTitle,
                ),
            )
        },
        viewModel = viewModel,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
    )
}
