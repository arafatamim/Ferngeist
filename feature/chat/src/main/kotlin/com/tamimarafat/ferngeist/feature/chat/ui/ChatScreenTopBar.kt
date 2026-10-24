package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun ChatScreenTopBar(
    screenState: ChatScreenState,
    sessionId: String,
    fallbackTitle: String,
    scrollBehavior: TopAppBarScrollBehavior,
    coroutineScope: CoroutineScope,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    showBackButton: Boolean = true,
    sharedBoundsEnabled: Boolean = true,
) {
    ChatTopBar(
        sessionId = sessionId,
        // The view model owns the title: it is the one place that knows whether a title
        // came from the agent or is just the prompt, so a rejected title must not fall
        // back to the string this screen was opened with.
        sessionTitle = screenState.state.title ?: fallbackTitle,
        cwd = viewModel.cwd,
        activeModel = screenState.derived.activeModel,
        connectionState = screenState.state.connectionState,
        totalTokens = screenState.state.usage?.totalTokens,
        contextWindowTokens = screenState.state.usage?.contextWindowTokens,
        costAmount = screenState.state.usage?.costAmount,
        costCurrency = screenState.state.usage?.costCurrency,
        gitAdditions = screenState.derived.gitAdditions,
        gitDeletions = screenState.derived.gitDeletions,
        gitBranch =
            screenState.state.git.status
                ?.branch,
        gitChangedFiles =
            screenState.state.git.status
                ?.changed
                ?.size ?: 0,
        gitRepo = screenState.state.git.status != null,
        // A managed worktree's counts are against its base ref, which is the only comparison that
        // means anything there; the status counts are against the upstream and stay zero without one.
        gitAhead =
            screenState.state.git.worktree
                ?.ahead
                ?: screenState.state.git.status
                    ?.ahead
                ?: 0,
        gitBehind =
            screenState.state.git.worktree
                ?.behind
                ?: screenState.state.git.status
                    ?.behind
                ?: 0,
        worktree = screenState.state.git.worktree,
        scrollBehavior = scrollBehavior,
        onNavigateBack = onNavigateBack,
        onConnectionStatusClick = { screenState.mutations.showConnectionStatusDialog.value = true },
        onGitStatusClick = { screenState.mutations.showGitStatusSheet.value = true },
        onGitStatusLongPress = { viewModel.dispatch(ChatIntent.RefreshGitStatus) },
        onTitleClick = {
            coroutineScope.launch {
                // Expand the bar alongside the scroll. A programmatic scroll never reaches the
                // bar's nested-scroll connection, so setting it after the jump popped it open
                // at the very end of the ease.
                launch {
                    animate(
                        initialValue = scrollBehavior.state.heightOffset,
                        targetValue = 0f,
                        animationSpec = tween(EASE_MIN_MS, easing = EaseInOutCubic),
                    ) { value, _ -> scrollBehavior.state.heightOffset = value }
                }
                screenState.derived.scrollHandle.jumpToTop()
            }
        },
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        showBackButton = showBackButton,
        sharedBoundsEnabled = sharedBoundsEnabled,
    )
}
