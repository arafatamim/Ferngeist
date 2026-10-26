package com.tamimarafat.ferngeist.workspace

import android.os.Bundle
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.defaultViewModelCreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamimarafat.ferngeist.feature.sessionlist.project.PROJECT_GATEWAY_ARG
import com.tamimarafat.ferngeist.feature.sessionlist.project.PROJECT_REPO_ARG
import com.tamimarafat.ferngeist.feature.sessionlist.project.ProjectScreen
import com.tamimarafat.ferngeist.feature.sessionlist.project.ProjectViewModel

/**
 * The workspace's middle pane for a project: [ProjectScreen] hosted by the workspace rather than
 * registered as a route, its arguments riding `DEFAULT_ARGS_KEY` as [WorkspaceSessionsPane]'s do.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun WorkspaceProjectPane(
    project: WorkspaceProject,
    workspace: WorkspaceState,
    showBackButton: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val viewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner to scope the project pane to"
        }
    val extras =
        remember(viewModelStoreOwner, project) {
            MutableCreationExtras(viewModelStoreOwner.defaultViewModelCreationExtras).apply {
                set(
                    DEFAULT_ARGS_KEY,
                    Bundle().apply {
                        putString(PROJECT_GATEWAY_ARG, project.gatewaySourceId)
                        putString(PROJECT_REPO_ARG, project.repo)
                    },
                )
            }
        }
    val viewModel: ProjectViewModel =
        viewModel(
            viewModelStoreOwner = viewModelStoreOwner,
            key = "workspace-project:${project.gatewaySourceId}:${project.repo}",
            factory = rememberHiltViewModelFactory(),
            extras = extras,
        )

    // Without the key, remembered sheet and scroll state would carry over from the previous project.
    key(project) {
        ProjectScreen(
            viewModel = viewModel,
            showBackButton = showBackButton,
            // The home and project stay visible side by side. Matching their titles would
            // register two visible owners of one key, as with the workspace chat title.
            sharedBoundsEnabled = false,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
            onNavigateBack = { workspace.backToAgents() },
            onNavigateToChat = { serverId, sessionId, cwd, updatedAt, title ->
                workspace.selectSession(
                    WorkspaceSelection(
                        serverId = serverId,
                        sessionId = sessionId,
                        cwd = cwd,
                        title = title.orEmpty(),
                        updatedAt = updatedAt,
                    ),
                )
            },
        )
    }
}
