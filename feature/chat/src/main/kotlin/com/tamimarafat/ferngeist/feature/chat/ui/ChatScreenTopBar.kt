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
        activeModel = screenState.activeModel,
        connectionState = screenState.state.connectionState,
        totalTokens = screenState.state.usage?.totalTokens,
        contextWindowTokens = screenState.state.usage?.contextWindowTokens,
        costAmount = screenState.state.usage?.costAmount,
        costCurrency = screenState.state.usage?.costCurrency,
        gitAdditions = screenState.gitAdditions,
        gitDeletions = screenState.gitDeletions,
        gitBranch = screenState.state.gitStatus?.branch,
        gitChangedFiles =
            screenState.state.gitStatus
                ?.changed
                ?.size ?: 0,
        gitRepo = screenState.state.gitStatus != null,
        // A managed worktree's counts are against its base ref, which is the only comparison that
        // means anything there; the status counts are against the upstream and stay zero without one.
        gitAhead = screenState.state.worktree?.ahead ?: screenState.state.gitStatus?.ahead ?: 0,
        gitBehind = screenState.state.worktree?.behind ?: screenState.state.gitStatus?.behind ?: 0,
        worktree = screenState.state.worktree,
        scrollBehavior = scrollBehavior,
        onNavigateBack = onNavigateBack,
        onConnectionStatusClick = { screenState.showConnectionStatusDialog.value = true },
        onGitStatusClick = { screenState.showGitStatusSheet.value = true },
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
                screenState.scrollHandle.jumpToTop()
            }
        },
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        showBackButton = showBackButton,
        sharedBoundsEnabled = sharedBoundsEnabled,
    )
}
