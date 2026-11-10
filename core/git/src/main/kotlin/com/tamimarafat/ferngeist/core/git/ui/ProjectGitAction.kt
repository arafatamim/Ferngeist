package com.tamimarafat.ferngeist.core.git.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tamimarafat.ferngeist.core.git.files.FileView
import com.tamimarafat.ferngeist.core.git.files.GitFileLoader
import com.tamimarafat.ferngeist.core.git.state.ProjectGitSelection
import com.tamimarafat.ferngeist.core.git.ui.git.GitCompareUi
import com.tamimarafat.ferngeist.core.git.ui.git.GitStatusSheet
import com.tamimarafat.ferngeist.core.model.git.ProjectGitReaderFactory
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import kotlinx.coroutines.launch

/** Target identity owns both the open sheet and all loaded data, including replies still in flight. */
@Composable
fun ProjectGitAction(
    target: ProjectGitTarget?,
    factory: ProjectGitReaderFactory,
) {
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            gitIndicatorEnter togetherWith gitIndicatorExit
        },
        label = "ProjectGitAction",
    ) { selected ->
        if (selected != null) ProjectGitTargetAction(selected, factory)
    }
}

@Composable
private fun ProjectGitTargetAction(
    target: ProjectGitTarget,
    factory: ProjectGitReaderFactory,
) {
    val scope = rememberCoroutineScope()
    val selection = remember(factory) { ProjectGitSelection(scope, factory) }
    val controller = remember(selection, target) { selection.select(target) } ?: return
    val reader = remember(target, factory) { factory.create(target) }
    val git by controller.state.collectAsState()
    var showSheet by remember(target) { mutableStateOf(false) }
    var openFile by remember(target) { mutableStateOf<FileView?>(null) }
    var softWrap by remember(target) { mutableStateOf(true) }
    var markdownPreview by remember(target) { mutableStateOf(false) }
    val files =
        remember(controller) {
            GitFileLoader(
                scope,
                reader,
                { openFile = it },
                target.cwd,
                {
                    controller.state.value.status
                        ?.changed
                        .orEmpty()
                },
            )
        }
    LaunchedEffect(controller) { controller.refresh() }
    val status = git.status
    GitIndicatorVisibility(visible = status != null) {
        if (status != null) {
            GitStatusIndicatorButton(
                additions = git.diffStats?.additions ?: 0,
                deletions = git.diffStats?.deletions ?: 0,
                branch = status.branch,
                changedFiles = status.changed.size,
                ahead = git.worktree?.ahead ?: status.ahead,
                behind = git.worktree?.behind ?: status.behind,
                worktree = git.worktree,
                onClick = { showSheet = true },
                onLongPress = controller::refresh,
            )
        }
    }
    if (showSheet && status != null) {
        GitStatusSheet(
            status = status,
            history = git.history,
            gitFileDiff = git.fileDiff,
            gitFileDiffPath = git.fileDiffPath,
            gitFileDiffCommit = git.fileDiffCommit,
            gitFileDiffCompare = git.fileDiffCompare,
            isGitFileDiffLoading = git.isFileDiffLoading,
            gitFileDiffError = git.fileDiffError,
            compare =
                GitCompareUi(
                    git.compareRef,
                    git.refs,
                    git.compareStatus,
                    git.compareStatusError,
                    controller::selectCompare,
                    controller::refresh,
                ),
            onLoadGitDiff = {
                path,
                commit,
                oldPath,
                ->
                scope.launch { controller.loadDiff(path, commit, oldPath) }
            },
            onLoadGitLog = controller::loadLog,
            onLoadGitCommit = controller::loadCommit,
            onOpenFile = files::open,
            onOpen = controller::open,
            onDismiss = { showSheet = false },
        )
    }
    openFile?.let { file ->
        FileSheet(file, softWrap, { softWrap = it }, markdownPreview, { markdownPreview = it }, files::close)
    }
}
