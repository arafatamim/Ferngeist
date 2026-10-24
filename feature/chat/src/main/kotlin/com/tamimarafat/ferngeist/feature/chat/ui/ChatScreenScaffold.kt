package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.common.ui.SessionSharedBoundsKey
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.OnSwitchSession
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.SwitcherUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun ChatScreenScaffold(
    screenState: ChatScreenState,
    sessionId: String,
    fallbackTitle: String,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onSwitchSession: OnSwitchSession,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    sharedBoundsEnabled: Boolean = true,
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Hover of a drag over this pane, reported by the content receiver below and read by the
    // composer's button: while it is true the button shows what the drop would do.
    var dropTargetActive by remember { mutableStateOf(false) }
    val nothingToAttachMessage = stringResource(R.string.chat_attach_nothing_found)

    // Suppressed while the workspace shows the session list beside this chat. The list's
    // session card registers both of these keys for the same session, so two nodes claiming
    // one key made the shell animate to nothing: the chat pane's top bar rendered with no
    // title, no model subtitle and no cwd, visible only in semantics. One claimant restores it.
    val transitionModifier =
        if (sharedBoundsEnabled) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState =
                        rememberSharedContentState(
                            key = SessionSharedBoundsKey(sessionId),
                        ),
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                )
            }
        } else {
            Modifier
        }

    Box(
        modifier =
            transitionModifier
                .fillMaxSize()
                .then(modifier)
                .background(MaterialTheme.colorScheme.surface)
                // The pane, not the composer: a drag anywhere over the conversation is a drop
                // onto the composer's attachments, and the field below still finds this
                // receiver on its ancestors for paste.
                .composerContentReceiver(
                    canSendImages = screenState.state.canSendImages,
                    canSendFiles = screenState.state.supportsEmbeddedContext,
                    onUris = screenState.actions.attachUris,
                    onDragHoverChange = { dropTargetActive = it },
                    onNothingToAttach = {
                        coroutineScope.launch {
                            screenState.mutations.snackbarHostState.showSnackbar(nothingToAttachMessage)
                        }
                    },
                ),
    ) {
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
        // Chrome-style switch: the whole chat rides the bubble drag 1:1 in the
        // layout phase (no recomposition per frame), springs back or flies off
        // on release. The surface behind is the plain background.
        val chatOffset = remember { Animatable(0f) }

        Scaffold(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset(chatOffset.value.roundToInt(), 0)
                    },
            containerColor = Color.Transparent,
            topBar = {
                ChatScreenTopBar(
                    screenState = screenState,
                    sessionId = sessionId,
                    fallbackTitle = fallbackTitle,
                    scrollBehavior = scrollBehavior,
                    coroutineScope = coroutineScope,
                    viewModel = viewModel,
                    onNavigateBack = onNavigateBack,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                    showBackButton = showBackButton,
                    sharedBoundsEnabled = sharedBoundsEnabled,
                )
            },
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize(),
            ) {
                ChatScreenOverlays(
                    screenState = screenState,
                    innerPadding = innerPadding,
                    coroutineScope = coroutineScope,
                    focusManager = focusManager,
                    viewModel = viewModel,
                    chatOffset = chatOffset,
                    onSwitchSession = onSwitchSession,
                    appBarScrollConnection = scrollBehavior.nestedScrollConnection,
                    dropTargetActive = dropTargetActive,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.ChatScreenOverlays(
    screenState: ChatScreenState,
    innerPadding: PaddingValues,
    coroutineScope: CoroutineScope,
    focusManager: FocusManager,
    viewModel: ChatViewModel,
    chatOffset: Animatable<Float, AnimationVector1D>,
    onSwitchSession: OnSwitchSession,
    appBarScrollConnection: NestedScrollConnection,
    dropTargetActive: Boolean,
) {
    val switcherState by viewModel.switcherUiState.collectAsStateWithLifecycle()
    ChatScreenContentOverlays(
        screenState = screenState,
        innerPadding = innerPadding,
        viewModel = viewModel,
        switcherState = switcherState,
        onSwitchSession = onSwitchSession,
        appBarScrollConnection = appBarScrollConnection,
    )
    ChatScreenSnackbar(screenState)

    if (screenState.insets.showComposerToolbar) {
        ChatComposerHost(
            screenState = screenState,
            coroutineScope = coroutineScope,
            focusManager = focusManager,
            viewModel = viewModel,
            switcherState = switcherState,
            chatOffset = chatOffset,
            onSwitchSession = onSwitchSession,
            onSwitcherClick = { screenState.mutations.showSwitcherSheet.value = true },
            dropTargetActive = dropTargetActive,
        )
    }
}

/** Opens a file in one step, or asks the reader to pick among the [candidates] a shared name could mean. */
@Composable
private fun rememberOpenFile(
    viewModel: ChatViewModel,
    hasWorkspace: Boolean,
    fileCandidates: MutableState<List<String>?>,
): ((List<String>) -> Unit)? =
    remember(viewModel, hasWorkspace) {
        if (hasWorkspace) {
            { candidates ->
                val only = candidates.singleOrNull()
                if (only != null) {
                    viewModel.dispatch(ChatIntent.OpenFile(only))
                } else {
                    fileCandidates.value = candidates
                }
            }
        } else {
            null
        }
    }

// The whole file is read at the revision of the diff it was opened from, so the git sheet says
// what that file is rather than always asking for the working tree.
@Composable
private fun rememberOpenFileAt(
    viewModel: ChatViewModel,
    hasWorkspace: Boolean,
): ((String, String?, String?, String?) -> Unit)? =
    remember(viewModel, hasWorkspace) {
        if (hasWorkspace) {
            { path, commit, compare, oldPath ->
                viewModel.dispatch(ChatIntent.OpenFile(path, commit, compare, oldPath))
            }
        } else {
            null
        }
    }

@Composable
private fun BoxScope.ChatScreenContentOverlays(
    screenState: ChatScreenState,
    innerPadding: PaddingValues,
    viewModel: ChatViewModel,
    switcherState: SwitcherUiState,
    onSwitchSession: OnSwitchSession,
    appBarScrollConnection: NestedScrollConnection,
) {
    // The tool call sheet reads the working tree's diff for an edit with none of its own, but only in
    // the newest turn: the tree holds everything since, so an older call's diff would be wrong.
    val selected =
        screenState.mutations.selectedToolCallSegmentId.value
            ?.selectedSegmentId()
    val inNewestTurn =
        screenState.state.messages
            .lastOrNull()
            ?.segments
            ?.any { it.id == selected } == true
    // The files a chip's shared name could mean, while the reader picks one.
    val fileCandidates = remember { mutableStateOf<List<String>?>(null) }
    // Reading preferences, not per-file: each keeps its choice for the next file. Markdown opens
    // rendered; the toggle is the way back to its source.
    var softWrapFile by rememberSaveable { mutableStateOf(false) }
    var markdownPreview by rememberSaveable { mutableStateOf(true) }
    // Opening a file needs a workspace to read it from; without a gateway there is none.
    val hasWorkspace = screenState.state.gatewayWorkspaceConnection != null
    val openFile = rememberOpenFile(viewModel, hasWorkspace, fileCandidates)
    val openFileAt = rememberOpenFileAt(viewModel, hasWorkspace)
    CompositionLocalProvider(
        localWorkingTreeChanges provides
            if (inNewestTurn) screenState.state.turnWorkingTree() else emptyList(),
        localTurnGitFallback provides
            TurnGitFallback(
                diffs = screenState.state.turnGitDiffs,
                loading = screenState.state.turnGitDiffsLoading,
                errors = screenState.state.turnGitDiffsErrors,
                onRequest = { path, oldPath, revision ->
                    viewModel.dispatch(ChatIntent.LoadTurnFileDiff(path, oldPath, revision))
                },
                onRefresh = { if (inNewestTurn) viewModel.dispatch(ChatIntent.RefreshGitStatus) },
            ),
        // The tool call sheet's open-full-file button wants the same opener the transcript chips use.
        localFileOpener provides openFile,
        localWorkspaceFiles provides screenState.state.workspaceFiles,
    ) {
        ChatScreenDialogsHost(screenState, viewModel)
    }
    fileCandidates.value?.let { candidates ->
        FilePickerSheet(
            candidates = candidates,
            onPick = { path ->
                fileCandidates.value = null
                viewModel.dispatch(ChatIntent.OpenFile(path))
            },
            onDismiss = { fileCandidates.value = null },
        )
    }
    if (screenState.mutations.showGitStatusSheet.value) {
        // The sheet is where the reader asks what the tree looks like, so every open re-reads the
        // status rather than showing whatever the last turn boundary or resume left in state.
        LaunchedEffect(Unit) { viewModel.dispatch(ChatIntent.RefreshGitStatus) }
        screenState.state.gitStatus?.let { status ->
            GitStatusSheet(
                status = status,
                history = screenState.state.gitHistory,
                gitFileDiff = screenState.state.gitFileDiff,
                gitFileDiffPath = screenState.state.gitFileDiffPath,
                gitFileDiffCommit = screenState.state.gitFileDiffCommit,
                gitFileDiffCompare = screenState.state.gitFileDiffCompare,
                isGitFileDiffLoading = screenState.state.isGitFileDiffLoading,
                gitFileDiffError = screenState.state.gitFileDiffError,
                compare =
                    GitCompareUi(
                        ref = screenState.state.compareRef,
                        refs = screenState.state.gitRefs,
                        status = screenState.state.compareStatus,
                        error = screenState.state.compareStatusError,
                        onSelect = { ref -> viewModel.dispatch(ChatIntent.SelectCompareRef(ref)) },
                        onRetry = { viewModel.dispatch(ChatIntent.RefreshGitStatus) },
                    ),
                onLoadGitDiff = { path, commit, oldPath ->
                    viewModel.dispatch(ChatIntent.LoadGitDiff(path, commit, oldPath))
                },
                onLoadGitLog = { more -> viewModel.dispatch(ChatIntent.LoadGitLog(more)) },
                onLoadGitCommit = { sha -> viewModel.dispatch(ChatIntent.LoadGitCommit(sha)) },
                onOpenFile = openFileAt,
                onOpen = {
                    viewModel.dispatch(ChatIntent.LoadGitRefs)
                    viewModel.dispatch(ChatIntent.RefreshGitStatus)
                },
                onDismiss = { screenState.mutations.showGitStatusSheet.value = false },
            )
        }
    }
    if (screenState.mutations.showSwitcherSheet.value) {
        SessionSwitcherSheet(
            uiState = switcherState,
            onSwitch = { session ->
                screenState.mutations.showSwitcherSheet.value = false
                onSwitchSession(session, null)
            },
            onClose = { session ->
                // Sheet stays open: the row disappears when the hub drops the
                // session, and a refusal lands in the snackbar behind it.
                viewModel.dispatch(ChatIntent.CloseSession(session.serverId, session.sessionId))
            },
            onDismiss = { screenState.mutations.showSwitcherSheet.value = false },
        )
    }
    // Last of the sheets so it stacks over whichever one opened it; the transcript chip opens it
    // over the chat, the tool call and git sheets open it over themselves.
    screenState.state.openFile?.let { file ->
        FileSheet(
            file = file,
            softWrap = softWrapFile,
            onSoftWrapChange = { softWrapFile = it },
            markdownPreview = markdownPreview,
            onMarkdownPreviewChange = { markdownPreview = it },
            onDismiss = { viewModel.dispatch(ChatIntent.CloseFile) },
        )
    }

    CompositionLocalProvider(
        localFileOpener provides openFile,
        localWorkspaceFiles provides screenState.state.workspaceFiles,
    ) {
        ChatScreenBody(
            state = screenState.state,
            scroll = screenState.derived.scrollHandle,
            appBarScrollConnection = appBarScrollConnection,
            renderedLastMessageId = screenState.derived.renderedLastMessageId,
            listTopPadding = innerPadding.calculateTopPadding(),
            listBottomPadding = screenState.insets.listBottomPadding,
            onRetryLoad = { viewModel.dispatch(ChatIntent.RetryLoad) },
            onThoughtClick = { segmentId ->
                screenState.mutations.selectedThoughtSegmentId.value = segmentId
            },
            onToolCallClick = { segmentId ->
                screenState.mutations.selectedToolCallSegmentId.value = segmentId
            },
            onRetryMessage = { clientId ->
                viewModel.dispatch(ChatIntent.RetryMessage(clientId))
            },
        )
    }

    ChatBottomFade(bandHeight = screenState.insets.bottomFadeBandHeight)

    ChatScreenSnackbar(screenState)
}

// How much of the surface colour the bottom fade reaches at the very bottom of
// the screen. Deliberately short of 1f: content going under the composer is
// dimmed, not dissolved, so it stays faintly readable the way it does when it
// passes under the top bar.
private const val BOTTOM_FADE_MAX_ALPHA = 0.75f

/**
 * Mirrors the top bar at the bottom of the chat: content going under the
 * composer is dimmed into the surface instead of being cut off by it.
 *
 * The band starts just above the composer's top edge and runs to the bottom of
 * the screen, so content is only dimmed once it reaches the composer — and, at
 * rest, the last message ends below the band's transparent edge and stays
 * crisp.
 *
 * Unlike the top bar, the band never reaches the surface colour: it bottoms out
 * at [BOTTOM_FADE_MAX_ALPHA], leaving what has scrolled behind the composer
 * faintly readable rather than dissolving it away.
 */
@Composable
private fun BoxScope.ChatBottomFade(bandHeight: Dp) {
    if (bandHeight <= 0.dp) return
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bandHeight)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to surface.copy(alpha = BOTTOM_FADE_MAX_ALPHA),
                    ),
                ),
    )
}

@Composable
private fun BoxScope.ChatScreenSnackbar(screenState: ChatScreenState) {
    SnackbarHost(
        hostState = screenState.mutations.snackbarHostState,
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = screenState.insets.snackbarBottomPadding,
                ).zIndex(2f),
    )
}
