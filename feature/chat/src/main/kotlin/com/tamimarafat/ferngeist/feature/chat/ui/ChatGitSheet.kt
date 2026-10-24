package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.rememberEscapeFocusAnchor
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.qualified
import com.tamimarafat.ferngeist.feature.chat.GitHistoryState
import com.tamimarafat.ferngeist.feature.chat.GitRefsState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.gitBranchScope
import com.tamimarafat.ferngeist.feature.chat.ui.diff.isDirectoryEntry
import com.tamimarafat.ferngeist.feature.chat.ui.diff.isDirectoryPath
import com.tamimarafat.ferngeist.feature.chat.ui.status.GitDiffDetailContent
import kotlinx.coroutines.delay

private const val DATE_TICK_MILLIS = 60_000L

/** What the git sheet compares against and the callbacks to change or retry it. */
internal class GitCompareUi(
    val ref: ChatGitRef?,
    val refs: GitRefsState,
    val status: ChatGitStatus?,
    val error: String?,
    val onSelect: (ChatGitRef?) -> Unit,
    val onRetry: () -> Unit,
) {
    /** The picker needs the gateway to have answered `/git/refs`; an older one hides the whole affordance. */
    val available: Boolean get() = refs.isLoaded && !refs.unsupported
}

/**
 * Modal bottom sheet for the chat's git working tree: a Changes tab (branch, totals, changed
 * files) and a History tab (commit graph), each drilling into a file diff or a commit.
 * Opened by long-pressing the git status pill in the chat top bar. Back and Escape step up
 * one level before they dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GitStatusSheet(
    status: ChatGitStatus,
    history: GitHistoryState,
    gitFileDiff: List<ToolCallContent.Diff>?,
    gitFileDiffPath: String?,
    gitFileDiffCommit: String?,
    gitFileDiffCompare: String?,
    isGitFileDiffLoading: Boolean,
    gitFileDiffError: String?,
    compare: GitCompareUi,
    onLoadGitDiff: (path: String, commit: String?, oldPath: String?) -> Unit,
    onLoadGitLog: (more: Boolean) -> Unit,
    onLoadGitCommit: (sha: String) -> Unit,
    /** Opens a file's whole content at the revision its diff was read from; null hides the control. */
    onOpenFile: ((path: String, commit: String?, compare: String?, oldPath: String?) -> Unit)?,
    /** Sheet opened: refresh what it shows, including the refs the picker lists. */
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    // Saved across process death so a drilled-in page survives rotation while its request runs.
    var route by rememberSaveable(
        stateSaver = gitSheetRouteSaver,
    ) { mutableStateOf<GitSheetRoute>(GitSheetRoute.Changes) }
    // Each page's scroll outlives its exit animation, so back lands where the reader left it.
    val pageStates = rememberSaveableStateHolder()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { onOpen() }

    GitSheetRequests(
        route = route,
        history = history,
        gitFileDiffPath = gitFileDiffPath,
        gitFileDiffCommit = gitFileDiffCommit,
        gitFileDiffCompare = gitFileDiffCompare,
        compare = compare.ref?.qualified,
        onLoadGitDiff = onLoadGitDiff,
        onLoadGitLog = onLoadGitLog,
        onLoadGitCommit = onLoadGitCommit,
        onRedirect = { route = it },
    )
    // The tab is hidden on a gateway without history, so nothing may be left showing it.
    LaunchedEffect(history.unsupported) { if (history.unsupported) route = GitSheetRoute.Changes }

    val motion = MaterialTheme.motionScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // Inside the sheet: it is its own window, so a handler outside it never sees back.
        BackHandler(enabled = route.depth > 0) { route.parent?.let { route = it } }
        AnimatedContent(
            targetState = route,
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Key events reach the active focus target and its ancestors only, so the
                    // sheet takes focus itself rather than relying on focusable content.
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey {
                        val parent = route.parent
                        if (parent != null) route = parent else scope.dismissSheet(sheetState, onDismiss)
                    },
            // Both tabs are one page; switching between them crossfades inside it.
            contentKey = { if (it.depth == 0) 0 else it },
            transitionSpec = { depthTransform(motion) },
            label = "GitStatusSheetContent",
        ) { target ->
            GitSheetPage(
                target = target,
                pageStates = pageStates,
                status = status,
                history = history,
                compare = compare,
                gitFileDiff = gitFileDiff,
                gitFileDiffPath = gitFileDiffPath,
                isGitFileDiffLoading = isGitFileDiffLoading,
                gitFileDiffError = gitFileDiffError,
                onLoadGitDiff = onLoadGitDiff,
                onLoadGitLog = onLoadGitLog,
                onLoadGitCommit = onLoadGitCommit,
                onOpenFile = onOpenFile,
                onPickCompare = { showPicker = true },
                onNavigate = { next ->
                    // Going deeper opens a page fresh; only going back restores one.
                    if (next.depth > route.depth) pageStates.removeState(next.stateKey)
                    route = next
                },
            )
        }
    }
    if (showPicker) {
        GitComparePicker(
            refs = compare.refs,
            selected = compare.ref,
            onSelect = compare.onSelect,
            onDismiss = { showPicker = false },
        )
    }
}

/** The page for one route; [onNavigate] moves the sheet, and taps dispatch their own request first. */
@Composable
private fun GitSheetPage(
    target: GitSheetRoute,
    pageStates: SaveableStateHolder,
    status: ChatGitStatus,
    history: GitHistoryState,
    compare: GitCompareUi,
    gitFileDiff: List<ToolCallContent.Diff>?,
    gitFileDiffPath: String?,
    isGitFileDiffLoading: Boolean,
    gitFileDiffError: String?,
    onLoadGitDiff: (path: String, commit: String?, oldPath: String?) -> Unit,
    onLoadGitLog: (more: Boolean) -> Unit,
    onLoadGitCommit: (sha: String) -> Unit,
    onOpenFile: ((path: String, commit: String?, compare: String?, oldPath: String?) -> Unit)?,
    onPickCompare: () -> Unit,
    onNavigate: (GitSheetRoute) -> Unit,
) {
    when (target) {
        GitSheetRoute.Changes, GitSheetRoute.History ->
            GitTabsRoute(
                target = target,
                pageStates = pageStates,
                status = status,
                history = history,
                compare = compare,
                onLoadGitDiff = onLoadGitDiff,
                onLoadGitLog = onLoadGitLog,
                onLoadGitCommit = onLoadGitCommit,
                onPickCompare = onPickCompare,
                onNavigate = onNavigate,
            )
        is GitSheetRoute.Commit ->
            pageStates.SaveableStateProvider(target.stateKey) {
                GitCommitPage(
                    sha = target.sha,
                    history = history,
                    onBack = { onNavigate(GitSheetRoute.History) },
                    onRetry = { onLoadGitCommit(target.sha) },
                    onFileClick = { file ->
                        onLoadGitDiff(file.path, target.sha, file.oldPath)
                        onNavigate(GitSheetRoute.FileDiff(file.path, target.sha, file.oldPath))
                    },
                )
            }
        is GitSheetRoute.FileDiff ->
            pageStates.SaveableStateProvider(target.stateKey) {
                GitFileDiffRoute(
                    target = target,
                    status = status,
                    history = history,
                    compare = compare,
                    gitFileDiff = gitFileDiff,
                    gitFileDiffPath = gitFileDiffPath,
                    isGitFileDiffLoading = isGitFileDiffLoading,
                    gitFileDiffError = gitFileDiffError,
                    onLoadGitDiff = onLoadGitDiff,
                    onOpenFile = onOpenFile,
                    onNavigate = onNavigate,
                )
            }
    }
}

@Composable
private fun GitTabsRoute(
    target: GitSheetRoute,
    pageStates: SaveableStateHolder,
    status: ChatGitStatus,
    history: GitHistoryState,
    compare: GitCompareUi,
    onLoadGitDiff: (path: String, commit: String?, oldPath: String?) -> Unit,
    onLoadGitLog: (more: Boolean) -> Unit,
    onLoadGitCommit: (sha: String) -> Unit,
    onPickCompare: () -> Unit,
    onNavigate: (GitSheetRoute) -> Unit,
) {
    GitSheetTabsPage(
        pageStates = pageStates,
        status = status,
        history = history,
        compare = compare,
        tab = if (target == GitSheetRoute.History) 1 else 0,
        onSelectTab = { selected ->
            if (selected == 1) onLoadGitLog(false)
            onNavigate(if (selected == 1) GitSheetRoute.History else GitSheetRoute.Changes)
        },
        onFileClick = { file ->
            if (!isDirectoryEntry(file)) {
                onLoadGitDiff(file.path, null, null)
                onNavigate(GitSheetRoute.FileDiff(file.path, null, null))
            }
        },
        onCommitClick = { sha ->
            if (sha != history.commitSha) onLoadGitCommit(sha)
            onNavigate(GitSheetRoute.Commit(sha))
        },
        onLoadGitLog = onLoadGitLog,
        onPickCompare = onPickCompare,
    )
}

@Composable
private fun GitFileDiffRoute(
    target: GitSheetRoute.FileDiff,
    status: ChatGitStatus,
    history: GitHistoryState,
    compare: GitCompareUi,
    gitFileDiff: List<ToolCallContent.Diff>?,
    gitFileDiffPath: String?,
    isGitFileDiffLoading: Boolean,
    gitFileDiffError: String?,
    onLoadGitDiff: (path: String, commit: String?, oldPath: String?) -> Unit,
    onOpenFile: ((path: String, commit: String?, compare: String?, oldPath: String?) -> Unit)?,
    onNavigate: (GitSheetRoute) -> Unit,
) {
    GitDiffDetailContent(
        path = target.path,
        file =
            when {
                target.commit != null -> history.commitFiles.orEmpty()
                // Target-only files exist in the comparison alone, not in the working-tree status.
                compare.ref != null -> compare.status?.changed.orEmpty()
                else -> status.changed
            }.firstOrNull { it.path == target.path },
        diff = gitFileDiff,
        diffPath = gitFileDiffPath,
        isLoading = isGitFileDiffLoading,
        error = gitFileDiffError,
        onBack = { onNavigate(target.parent) },
        onRetry = { onLoadGitDiff(target.path, target.commit, target.oldPath) },
        inCommit = target.commit != null,
        onOpenFile =
            onOpenFile?.let { open ->
                {
                    // The file is read at the revision the diff came from, so the
                    // whole file agrees with what the reader was just looking at.
                    open(
                        target.path,
                        target.commit,
                        if (target.commit == null) compare.ref?.qualified else null,
                        target.oldPath,
                    )
                }
            },
    )
}

/**
 * Re-requests what a restored or changed route shows, unless the view model already holds it;
 * a row tap dispatches its own request first, so nothing is sent twice.
 */
@Composable
private fun GitSheetRequests(
    route: GitSheetRoute,
    history: GitHistoryState,
    gitFileDiffPath: String?,
    gitFileDiffCommit: String?,
    gitFileDiffCompare: String?,
    /** The qualified target the working-tree side is read against; null for the working tree itself. */
    compare: String?,
    onLoadGitDiff: (path: String, commit: String?, oldPath: String?) -> Unit,
    onLoadGitLog: (more: Boolean) -> Unit,
    onLoadGitCommit: (sha: String) -> Unit,
    onRedirect: (GitSheetRoute) -> Unit,
) {
    LaunchedEffect(route) {
        val sha = route.commitSha
        if ((sha != null || route == GitSheetRoute.History) && !history.isLoaded) onLoadGitLog(false)
        if (sha != null && sha != history.commitSha) onLoadGitCommit(sha)
    }
    if (route is GitSheetRoute.FileDiff) {
        FileDiffRequest(
            route = route,
            gitFileDiffPath = gitFileDiffPath,
            gitFileDiffCommit = gitFileDiffCommit,
            gitFileDiffCompare = gitFileDiffCompare,
            compare = compare,
            onLoadGitDiff = onLoadGitDiff,
            onRedirect = onRedirect,
        )
    }
}

/** Reads the diff a file route shows unless the one on screen was read under the same target. */
@Composable
private fun FileDiffRequest(
    route: GitSheetRoute.FileDiff,
    gitFileDiffPath: String?,
    gitFileDiffCommit: String?,
    gitFileDiffCompare: String?,
    compare: String?,
    onLoadGitDiff: (path: String, commit: String?, oldPath: String?) -> Unit,
    onRedirect: (GitSheetRoute) -> Unit,
) {
    LaunchedEffect(route) {
        val sha = route.commitSha
        val wantedCompare = if (sha == null) compare else null
        when {
            sha == null && isDirectoryPath(route.path) -> onRedirect(GitSheetRoute.Changes)
            // A diff read under one target is never shown under another.
            route.path != gitFileDiffPath ||
                sha != gitFileDiffCommit ||
                wantedCompare != gitFileDiffCompare -> onLoadGitDiff(route.path, sha, route.oldPath)
        }
    }
}

/** Depth reads as moving right; back mirrors it, and exits are quicker than entries. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AnimatedContentTransitionScope<GitSheetRoute>.depthTransform(motion: MotionScheme): ContentTransform {
    val direction = targetState.depth.compareTo(initialState.depth)
    val enter =
        if (direction == 0) {
            fadeIn(motion.fastEffectsSpec())
        } else {
            slideInHorizontally(motion.defaultSpatialSpec<IntOffset>()) { direction * it / 4 } +
                fadeIn(motion.defaultEffectsSpec())
        }
    return (enter togetherWith fadeOut(motion.fastEffectsSpec())).using(SizeTransform(clip = false))
}

/** Header and tabs stay put while the body below crossfades between Changes and History. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GitSheetTabsPage(
    pageStates: SaveableStateHolder,
    status: ChatGitStatus,
    history: GitHistoryState,
    compare: GitCompareUi,
    tab: Int,
    onSelectTab: (Int) -> Unit,
    onFileClick: (ChatChangedFile) -> Unit,
    onCommitClick: (String) -> Unit,
    onLoadGitLog: (more: Boolean) -> Unit,
    onPickCompare: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        GitStatusHeader(
            status = status,
            compareRef = compare.ref,
            canCompare = compare.available,
            onPickCompare = onPickCompare,
        )
        if (!history.unsupported) {
            Spacer(modifier = Modifier.height(12.dp))
            GitSheetTabs(selected = tab, onSelect = onSelectTab, total = history.total)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Crossfade(
            targetState = tab,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            modifier = Modifier.weight(1f),
            label = "GitSheetTab",
        ) { shown ->
            val shownRoute = if (shown == 1) GitSheetRoute.History else GitSheetRoute.Changes
            pageStates.SaveableStateProvider(shownRoute.stateKey) {
                Column {
                    when {
                        shown == 1 ->
                            GitHistoryBody(
                                history = history,
                                onCommitClick = onCommitClick,
                                onLoadGitLog = onLoadGitLog,
                            )
                        compare.ref != null ->
                            GitCompareChangesBody(
                                target = compare.ref.name,
                                status = compare.status,
                                error = compare.error,
                                onRetry = compare.onRetry,
                                onFileClick = onFileClick,
                            )
                        else -> GitChangesBody(status = status, onFileClick = onFileClick)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GitHistoryBody(
    history: GitHistoryState,
    onCommitClick: (String) -> Unit,
    onLoadGitLog: (more: Boolean) -> Unit,
) {
    // Ticks so relative dates like "2m" keep up while the sheet stays open.
    val nowMillis by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(DATE_TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    // A page still in flight may yet reach the base commit, so only the loaded pages can say
    // where this branch's own work stops.
    val scope =
        remember(history.commits, history.hasMore, history.base) {
            gitBranchScope(history.commits, history.base, history.hasMore)
        }
    when {
        history.commits.isEmpty() && history.error != null ->
            ErrorStateCard(
                headline = stringResource(R.string.chat_git_history_error_title),
                body = history.error,
                icon = Icons.Rounded.CloudOff,
                medallionContainer = MaterialTheme.colorScheme.errorContainer,
                medallionContent = MaterialTheme.colorScheme.onErrorContainer,
                medallionShape = MaterialShapes.VerySunny.toShape(),
                ctaLabel = stringResource(R.string.chat_retry),
                onCta = { onLoadGitLog(false) },
                modifier = Modifier.padding(vertical = 16.dp),
            )
        !history.isLoaded -> GitHistorySkeleton()
        history.commits.isEmpty() ->
            Text(
                text = stringResource(R.string.chat_git_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        else ->
            GitHistoryList(
                commits = history.commits,
                nowMillis = nowMillis,
                onCommitClick = { onCommitClick(it.sha) },
                isLoadingMore = history.isLoadingMore,
                onLoadMore = { onLoadGitLog(true) },
                moreError = history.moreError,
                scope = scope,
            )
    }
}

/**
 * A commit from the loaded history. After process death the history reloads first, so the
 * page shows the file-list skeleton until its commit is back in the list.
 */
@Composable
private fun GitCommitPage(
    sha: String,
    history: GitHistoryState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFileClick: (ChatChangedFile) -> Unit,
) {
    val commit = history.commits.firstOrNull { it.sha == sha }
    if (commit == null) {
        // shortcut: a commit past page 1 is not reloaded after process death, so the page goes back to
        // History once page 1 lands without it; load pages until it appears if that ever matters.
        LaunchedEffect(history.isLoaded, history.isLoading) { if (history.isLoaded && !history.isLoading) onBack() }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { GitFileListSkeleton() }
        return
    }
    GitCommitDetailContent(
        commit = commit,
        remoteUrl = history.remoteUrl,
        files = history.commitFiles.takeIf { history.commitSha == sha },
        isLoading = history.isCommitLoading,
        error = history.commitError,
        onBack = onBack,
        onRetry = onRetry,
        onFileClick = { file -> if (!isDirectoryEntry(file)) onFileClick(file) },
    )
}
