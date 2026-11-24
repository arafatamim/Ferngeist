package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.core.model.hostPath
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.OnSwitchSession
import com.tamimarafat.ferngeist.feature.chat.SwitcherUiState
import com.tamimarafat.ferngeist.feature.chat.badgeStatus
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ComposerSuggestion
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ComposerSuggestionState
import com.tamimarafat.ferngeist.feature.chat.ui.composer.matchingSuggestions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// The FAB transform starts within a frame or two of a collapse; past this none is coming.
private const val TRANSFORM_START_TIMEOUT_MS = 100L

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.ChatComposerHost(
    screenState: ChatScreenState,
    coroutineScope: CoroutineScope,
    focusManager: FocusManager,
    viewModel: ChatViewModel,
    switcherState: SwitcherUiState,
    chatOffset: Animatable<Float, AnimationVector1D>,
    onSwitchSession: OnSwitchSession,
    onSwitcherClick: () -> Unit,
    dropTargetActive: Boolean,
) {
    val switcherHintSeen by viewModel.switcherHintSeen.collectAsStateWithLifecycle()
    val callbacks =
        rememberComposerCallbacks(
            screenState = screenState,
            coroutineScope = coroutineScope,
            viewModel = viewModel,
        )
    val switcherDrag =
        rememberSwitcherDragHandlers(
            switcherState = switcherState,
            chatOffset = chatOffset,
            coroutineScope = coroutineScope,
            onSwitchSession = onSwitchSession,
        )
    val composerDragModifier =
        if (!screenState.mutations.composerMode.value.isExpanded && switcherState.hasOthers) {
            Modifier.switcherDrag(
                onDragStart = {
                    if (!switcherHintSeen) {
                        viewModel.dispatch(ChatIntent.MarkSwitcherHintSeen)
                    }
                    switcherDrag.onDragStart()
                },
                onDragDelta = switcherDrag.onDragDelta,
                onDragStopped = switcherDrag.onDragStopped,
                onDragCancel = switcherDrag.onDragCancel,
            )
        } else {
            Modifier
        }
    // The transcript's bottom padding has to clear both the bar and the pinned queue above it.
    var barHeightPx by remember { mutableIntStateOf(0) }
    var queueHeightPx by remember { mutableIntStateOf(0) }
    // Keep the spacer steady when reading history so resizing the FAB can't move the jump threshold.
    val transcriptBarHeightPx = with(LocalDensity.current) { maxOf(barHeightPx, mediumComposerFabSize.roundToPx()) }
    LaunchedEffect(transcriptBarHeightPx, queueHeightPx) {
        screenState.mutations.composerContentHeightPx.value = transcriptBarHeightPx + queueHeightPx
    }
    // The bar's height eased with the same spring as the panel's bounds, for the queue above to follow.
    val dragDistance = remember(screenState.mutations.composerMode.value) { mutableFloatStateOf(0f) }
    val settledBarPx = remember { Animatable(0f) }
    val barSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(barHeightPx) {
        val target = barHeightPx.toFloat()
        if (settledBarPx.value == 0f) settledBarPx.snapTo(target) else settledBarPx.animateTo(target, barSpec)
    }
    // Shared bounds are tracked relative to this layout, so it fills the screen: the column below
    // is bottom-anchored and grows upward as the composer expands, and a scope that moved with it
    // would replay the FAB's start position from a shifted origin. It takes no input itself.
    SharedTransitionLayout(Modifier.matchParentSize().zIndex(1f)) {
        Box(Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .offset(y = -FloatingToolbarDefaults.ScreenOffset),
            ) {
                PendingPromptQueue(
                    messages = screenState.state.pendingMessages,
                    onRetryMessage = { viewModel.dispatch(ChatIntent.RetryMessage(it)) },
                    onForceSendMessage = { viewModel.dispatch(ChatIntent.ForceSendMessage(it)) },
                    onRemoveMessage = { viewModel.dispatch(ChatIntent.RemoveQueuedMessage(it)) },
                    onRestingHeight = { queueHeightPx = it },
                )
                Box(
                    Modifier.easeHeight(
                        settledHeightPx = { settledBarPx.value },
                        dragPx = { dragDistance.floatValue },
                    ),
                ) {
                    ChatComposerRow(
                        screenState = screenState,
                        focusManager = focusManager,
                        viewModel = viewModel,
                        switcherState = switcherState,
                        switcherHintSeen = switcherHintSeen,
                        callbacks = callbacks,
                        switcherDrag = switcherDrag,
                        onSwitcherClick = onSwitcherClick,
                        dropTargetActive = dropTargetActive,
                        onBarHeightChanged = { barHeightPx = it },
                        dragDistance = dragDistance,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        modifier = composerDragModifier,
                    )
                }
            }
        }
    }
}

/**
 * Reports at most [settledHeightPx] plus the panel's [dragPx]. A closing panel keeps its full layout
 * until its shared-bounds transform ends and an opening one takes it at once, so the queue stacked
 * above would hang in mid air or jump up, then fix itself; this lets it follow the eased height
 * instead. The row stays bottom-aligned and overflows up.
 */
internal fun Modifier.easeHeight(
    settledHeightPx: () -> Float,
    dragPx: () -> Float,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val settled = settledHeightPx()
        val height = if (settled > 0f) minOf(placeable.height, (settled + dragPx()).roundToInt()) else placeable.height
        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatComposerRow(
    screenState: ChatScreenState,
    focusManager: FocusManager,
    viewModel: ChatViewModel,
    switcherState: SwitcherUiState,
    switcherHintSeen: Boolean,
    callbacks: ComposerCallbacks,
    switcherDrag: SwitcherDragHandlers,
    onSwitcherClick: () -> Unit,
    dropTargetActive: Boolean,
    onBarHeightChanged: (Int) -> Unit,
    dragDistance: MutableFloatState,
    sharedTransitionScope: SharedTransitionScope,
    // Applied to the composer bar, not the row: it carries the switcher drag.
    modifier: Modifier = Modifier,
) {
    val mentionState = rememberComposerSuggestionState(screenState)
    val composerExpanded = screenState.mutations.composerMode.value.isExpanded
    // A collapse composes the bubble one frame before the FAB transform reports itself active,
    // which would flash the bubble above the still-tall panel. Hold it back until the transform
    // has started, or briefly when none runs (closing the fullscreen dialog).
    var transformStarted by remember { mutableStateOf(true) }
    LaunchedEffect(composerExpanded) {
        if (composerExpanded) {
            transformStarted = false
        } else if (!transformStarted) {
            withTimeoutOrNull(TRANSFORM_START_TIMEOUT_MS) {
                snapshotFlow { sharedTransitionScope.isTransitionActive }.first { it }
            }
            transformStarted = true
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The FAB anchors the row to the screen's trailing edge, where M3 places it. The gap to
        // the bubble lives in the bubble, so it grows with the entrance instead of snapping in.
        horizontalArrangement = Arrangement.End,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FloatingToolbarDefaults.ScreenOffset),
    ) {
        // Opening drops the bubble outright: the panel takes the full row the moment it lays
        // out, so an exit animation would be shoved to the row's start and play there.
        if (!composerExpanded) {
            SessionSwitcherBubble(
                count = switcherState.liveTotalCount,
                status = switcherState.badgeStatus,
                groups = switcherState.groups,
                // The exiting panel keeps its full-width layout until the FAB transform ends, so an
                // earlier entrance lands beside the wide panel and jumps once the bar shrinks back.
                // Waiting for it also lets the keyboard's dismissal settle, so the entrance does
                // not sum with the row's downward travel into a diagonal.
                visible =
                    switcherState.hasOthers &&
                        transformStarted &&
                        !sharedTransitionScope.isTransitionActive,
                hintSeen = switcherHintSeen,
                onHintSeen = { viewModel.dispatch(ChatIntent.MarkSwitcherHintSeen) },
                onClick = onSwitcherClick,
                onDragStart = switcherDrag.onDragStart,
                onDragDelta = switcherDrag.onDragDelta,
                onDragStopped = switcherDrag.onDragStopped,
                onDragCancel = switcherDrag.onDragCancel,
            )
        }
        ChatComposerBar(
            // `weight(fill = false)`: a Row measures its non-weighted children first, so the
            // switcher bubble claims its square and the composer takes what is left. Without
            // it the composer is measured first against the full row and, being content-driven,
            // clamps to the whole row on a narrow window, leaving the switcher a zero-width
            // sliver. `fill = false` keeps the pill hugging its actions instead of stretching
            // to the share.
            modifier = modifier.weight(1f, fill = false),
            state = screenState.state,
            toolbarConfigOptions = screenState.derived.toolbarConfigOptions,
            mode = screenState.mutations.composerMode.value,
            onModeChange = { screenState.mutations.composerMode.value = it },
            messageText = screenState.mutations.messageText,
            mentionState = mentionState,
            onMentionSelected = { path, start -> selectComposerSuggestion(screenState, viewModel, path, start) },
            inputAlpha = { screenState.derived.inputAlphaState.value },
            buttonsAlpha = { screenState.derived.buttonsAlphaState.value },
            showModeButton = screenState.derived.showModeButton,
            modeOption = screenState.derived.modeOption,
            currentModeLabel = screenState.derived.currentModeLabel,
            showStopAction = screenState.derived.showStopAction,
            focusRequester = screenState.mutations.focusRequester,
            focusEditor = {
                val mutations = screenState.mutations
                LaunchedEffect(
                    mutations.imageFocusTrigger.value,
                    mutations.composerFocusTrigger.value,
                    mutations.showCommandsDialog.value,
                ) {
                    if (!mutations.showCommandsDialog.value) mutations.focusRequester.requestFocus()
                }
            },
            onFocusCleared = { focusManager.clearFocus() },
            onHeightChanged = onBarHeightChanged,
            dragDistance = dragDistance,
            onSend = screenState.actions.sendMessage,
            onCancelStreaming = callbacks.onCancelStreaming,
            onSetStringConfigOption = callbacks.onSetStringConfigOption,
            onSetBooleanConfigOption = callbacks.onSetBooleanConfigOption,
            onShowCommands = callbacks.onShowCommands,
            onShowConfigOptionPicker = callbacks.onShowConfigOptionPicker,
            showJumpToBottom = screenState.derived.showJumpToBottom.value,
            onJumpToBottom = callbacks.onJumpToBottom,
            canSendImages = screenState.state.canSendImages,
            selectedImages = screenState.mutations.selectedImages.value,
            onImagesChanged = { screenState.mutations.selectedImages.value = it },
            canSendFiles = screenState.state.supportsEmbeddedContext,
            selectedFiles = screenState.mutations.selectedFiles.value,
            onFilesChanged = { screenState.mutations.selectedFiles.value = it },
            canSendAudio = screenState.state.canSendAudio,
            selectedAudios = screenState.mutations.selectedAudios.value,
            onAudiosChanged = { screenState.mutations.selectedAudios.value = it },
            selectedResourceLinks = screenState.mutations.selectedResourceLinks.value,
            onResourceLinksChanged = { screenState.mutations.selectedResourceLinks.value = it },
            attachmentPickers = screenState.actions.attachmentPickers,
            dropTargetActive = dropTargetActive,
            onAttachUris = screenState.actions.attachUris,
            snackbarHostState = screenState.mutations.snackbarHostState,
            sharedTransitionScope = sharedTransitionScope,
        )
    }
}

@Composable
private fun rememberComposerSuggestionState(screenState: ChatScreenState): ComposerSuggestionState {
    val files =
        remember(screenState.state.workspaceFiles) {
            screenState.state.workspaceFiles
                ?.values
                ?.flatten()
                ?.distinct()
                ?.sorted()
                .orEmpty()
        }
    val text = screenState.mutations.messageText
    val commandQuery =
        if (text.selection.collapsed) slashCommandQuery(text.text.toString(), text.selection.end) else null
    val mention =
        if (text.selection.collapsed) {
            workspaceMentionQuery(text.text.toString(), text.selection.end)
        } else {
            null
        }
    val suggestions =
        remember(files, mention, commandQuery, screenState.state.availableCommands) {
            if (commandQuery != null) {
                val commands = screenState.state.availableCommands
                matchingSuggestions(commands.map { it.name }, commandQuery).take(8).map { name ->
                    val command = commands.first { it.name == name }
                    ComposerSuggestion(
                        value = name,
                        label = command.inputHint?.let { "$name [$it]" } ?: name,
                        description = command.description,
                    )
                }
            } else {
                mention?.let { matchingSuggestions(files, it.second).take(8) }.orEmpty().map { path ->
                    ComposerSuggestion(
                        path,
                        path.substringAfterLast('/'),
                        path.substringBeforeLast('/', "").ifEmpty { null },
                    )
                }
            }
        }
    return remember(mention, commandQuery, suggestions, screenState.mutations.composerMode.value.isExpanded) {
        ComposerSuggestionState(
            if (commandQuery != null) 0 else mention?.first,
            suggestions,
            screenState.mutations.composerMode.value.isExpanded,
        )
    }
}

private fun selectComposerSuggestion(
    screenState: ChatScreenState,
    viewModel: ChatViewModel,
    path: String,
    start: Int?,
) {
    if (start == null) return
    if (screenState.mutations.messageText.text[start] == '/') {
        val text = screenState.mutations.messageText
        val separator = if (text.selection.end < text.text.length) "" else " "
        text.edit { replace(start, selection.end, "/$path$separator") }
        return
    }
    val absolute = hostPath(viewModel.cwd, path)
    val uri =
        java.net
            .URI(
                "file",
                "",
                if (absolute.startsWith('/')) absolute else "/$absolute",
                null,
            ).toASCIIString()
    screenState.mutations.attachResourceLink(ChatPromptResourceLink(name = path.substringAfterLast('/'), uri = uri))
    screenState.mutations.messageText.edit { replace(start, selection.end, "") }
}
