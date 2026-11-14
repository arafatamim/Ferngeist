package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.core.model.hostPath
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.OnSwitchSession
import com.tamimarafat.ferngeist.feature.chat.SwitcherUiState
import com.tamimarafat.ferngeist.feature.chat.badgeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Settle wait before the switcher bubble slides in after a collapse: covers
// the keyboard dismissal plus the pill shrink, so the entrance reads purely
// horizontal instead of summing with the row's downward travel.
private const val SWITCHER_COLLAPSE_SETTLE_DELAY_MS = 250L

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
    // Staggers the bubble entrance after a collapse: closing clears focus, so
    // the keyboard dismisses and the whole row travels down while the pill
    // shrinks. Entering mid-travel sums to a diagonal (the "top-right"
    // fly-in); waiting out the settle keeps the slide purely horizontal.
    // Skipped on first open — the row never traveled, so no wait is needed.
    var switcherEntranceReady by remember { mutableStateOf(true) }
    var composerWasExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(screenState.mutations.composerExpanded.value) {
        if (screenState.mutations.composerExpanded.value) {
            composerWasExpanded = true
            switcherEntranceReady = false
        } else if (composerWasExpanded) {
            delay(SWITCHER_COLLAPSE_SETTLE_DELAY_MS)
            switcherEntranceReady = true
        }
    }
    val switcherDrag =
        rememberSwitcherDragHandlers(
            switcherState = switcherState,
            chatOffset = chatOffset,
            coroutineScope = coroutineScope,
            onSwitchSession = onSwitchSession,
        )
    val composerDragModifier =
        if (!screenState.mutations.composerExpanded.value && switcherState.hasOthers) {
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
    LaunchedEffect(barHeightPx, queueHeightPx) {
        screenState.mutations.composerContentHeightPx.value = barHeightPx + queueHeightPx
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .offset(y = -FloatingToolbarDefaults.ScreenOffset)
                .zIndex(1f),
    ) {
        PendingPromptQueue(
            messages = screenState.state.pendingMessages,
            onRetryMessage = { viewModel.dispatch(ChatIntent.RetryMessage(it)) },
            onForceSendMessage = { viewModel.dispatch(ChatIntent.ForceSendMessage(it)) },
            onRemoveMessage = { viewModel.dispatch(ChatIntent.RemoveQueuedMessage(it)) },
            onRestingHeight = { queueHeightPx = it },
        )
        ChatComposerRow(
            screenState = screenState,
            focusManager = focusManager,
            viewModel = viewModel,
            switcherState = switcherState,
            switcherHintSeen = switcherHintSeen,
            switcherEntranceReady = switcherEntranceReady,
            callbacks = callbacks,
            switcherDrag = switcherDrag,
            onSwitcherClick = onSwitcherClick,
            dropTargetActive = dropTargetActive,
            onBarHeightChanged = { barHeightPx = it },
            modifier = composerDragModifier,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatComposerRow(
    screenState: ChatScreenState,
    focusManager: FocusManager,
    viewModel: ChatViewModel,
    switcherState: SwitcherUiState,
    switcherHintSeen: Boolean,
    switcherEntranceReady: Boolean,
    callbacks: ComposerCallbacks,
    switcherDrag: SwitcherDragHandlers,
    onSwitcherClick: () -> Unit,
    dropTargetActive: Boolean,
    onBarHeightChanged: (Int) -> Unit,
    // Applied to the composer bar, not the row: it carries the switcher drag.
    modifier: Modifier = Modifier,
) {
    val mentionState = rememberWorkspaceMentionState(screenState)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.spacedBy(
                space = 8.dp,
                alignment = Alignment.CenterHorizontally,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    mentionState.onKey(event) { path, start ->
                        selectWorkspaceMention(screenState, viewModel, path, start)
                    }
                }.padding(horizontal = 1.dp),
    ) {
        ChatComposerBar(
            // `weight(fill = false)`: a Row measures its non-weighted children first, so the
            // switcher bubble claims its square and the composer takes what is left. Without
            // it the composer is measured first against the full row and — being content-driven
            // and allowed to reach 92% of that width — clamps to the whole row on a narrow
            // window, leaving the switcher a zero-width sliver. `fill = false` keeps the pill
            // hugging its actions instead of stretching to the share.
            modifier = modifier.weight(1f, fill = false),
            state = screenState.state,
            toolbarConfigOptions = screenState.derived.toolbarConfigOptions,
            composerExpanded = screenState.mutations.composerExpanded.value,
            onComposerExpandedChange = { screenState.mutations.composerExpanded.value = it },
            messageText = screenState.mutations.messageText,
            mentionState = mentionState,
            onMentionSelected = { path, start -> selectWorkspaceMention(screenState, viewModel, path, start) },
            inputAlpha = { screenState.derived.inputAlphaState.value },
            buttonsAlpha = { screenState.derived.buttonsAlphaState.value },
            showModeButton = screenState.derived.showModeButton,
            modeOption = screenState.derived.modeOption,
            currentModeLabel = screenState.derived.currentModeLabel,
            showStopAction = screenState.derived.showStopAction,
            canCancelStreaming = screenState.derived.canCancelStreaming,
            focusRequester = screenState.mutations.focusRequester,
            onFocusCleared = { focusManager.clearFocus() },
            onHeightChanged = onBarHeightChanged,
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
            onAttach = { screenState.actions.attachmentPickerLauncher.launch(arrayOf("*/*")) },
            dropTargetActive = dropTargetActive,
        )
        if (!screenState.mutations.composerExpanded.value) {
            SessionSwitcherBubble(
                count = switcherState.liveTotalCount,
                status = switcherState.badgeStatus,
                groups = switcherState.groups,
                visible = switcherState.hasOthers && switcherEntranceReady,
                hintSeen = switcherHintSeen,
                onHintSeen = { viewModel.dispatch(ChatIntent.MarkSwitcherHintSeen) },
                onClick = onSwitcherClick,
                onDragStart = switcherDrag.onDragStart,
                onDragDelta = switcherDrag.onDragDelta,
                onDragStopped = switcherDrag.onDragStopped,
                onDragCancel = switcherDrag.onDragCancel,
            )
        }
    }
}

@Composable
private fun rememberWorkspaceMentionState(screenState: ChatScreenState): WorkspaceMentionState {
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
    val mention =
        if (text.selection.collapsed) {
            workspaceMentionQuery(text.text.toString(), text.selection.end)
        } else {
            null
        }
    val suggestions =
        remember(files, mention) {
            mention?.let { matchingWorkspaceFiles(files, it.second).take(8) }.orEmpty()
        }
    return remember(mention, suggestions, screenState.mutations.composerExpanded.value) {
        WorkspaceMentionState(mention?.first, suggestions, screenState.mutations.composerExpanded.value)
    }
}

internal class WorkspaceMentionState(
    val start: Int?,
    val suggestions: List<String>,
    expanded: Boolean,
) {
    var visible by mutableStateOf(expanded && start != null && suggestions.isNotEmpty())
    var selected by mutableIntStateOf(0)

    fun onKey(
        event: KeyEvent,
        onSelect: (String, Int?) -> Unit,
    ): Boolean {
        if (!visible || event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isMetaPressed) return false
        if (event.isAltPressed || event.isShiftPressed) return false
        return when (event.key) {
            Key.DirectionDown -> {
                selected = moveMentionSelection(selected, 1, suggestions.size)
                true
            }
            Key.DirectionUp -> {
                selected = moveMentionSelection(selected, -1, suggestions.size)
                true
            }
            Key.Enter, Key.NumPadEnter, Key.Tab -> {
                visible = false
                onSelect(suggestions[selected], start)
                true
            }
            Key.Escape -> {
                visible = false
                true
            }
            else -> false
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WorkspaceMentionMenu(
    state: WorkspaceMentionState,
    anchor: IntOffset,
    onSelect: (String, Int?) -> Unit,
) {
    var lastSuggestions by remember { mutableStateOf(state.suggestions) }
    var lastSelected by remember { mutableIntStateOf(state.selected) }
    SideEffect {
        if (state.visible) {
            lastSuggestions = state.suggestions
            lastSelected = state.selected
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (state.visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "mention menu",
    )
    val position = rememberMentionPosition(anchor, state.visible)
    if (!state.visible && alpha <= 0f) return
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { state.visible = false },
        properties = PopupProperties(focusable = false),
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, 1),
            modifier =
                Modifier
                    .graphicsLayer { this.alpha = alpha }
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            val suggestions = if (state.visible) state.suggestions else lastSuggestions
            val highlighted = if (state.visible) state.selected else lastSelected
            WorkspaceMentionItems(suggestions, highlighted, state, onSelect)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberMentionPosition(
    anchor: IntOffset,
    visible: Boolean,
): PopupPositionProvider {
    val menuPosition = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Above)
    return remember(menuPosition, anchor, visible) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val bounds = if (visible) anchorBounds else IntRect(anchor.x, anchor.y, anchor.x, anchor.y)
                return menuPosition.calculatePosition(bounds, windowSize, layoutDirection, popupContentSize)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkspaceMentionItems(
    suggestions: List<String>,
    highlighted: Int,
    state: WorkspaceMentionState,
    onSelect: (String, Int?) -> Unit,
) {
    suggestions.forEachIndexed { index, path ->
        val requester = remember(path) { BringIntoViewRequester() }
        val selected = index == highlighted
        LaunchedEffect(selected, state.visible) {
            if (selected && state.visible) requester.bringIntoView()
        }
        SelectableDropdownMenuItem(
            selected = selected,
            shapes = MenuDefaults.itemShape(index, suggestions.size),
            text = { Text(path.substringAfterLast('/')) },
            supportingText =
                path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { parent ->
                    { Text(parent) }
                },
            modifier = Modifier.bringIntoViewRequester(requester),
            onClick = {
                if (state.visible) {
                    state.visible = false
                    onSelect(path, state.start)
                }
            },
        )
    }
}

private fun selectWorkspaceMention(
    screenState: ChatScreenState,
    viewModel: ChatViewModel,
    path: String,
    start: Int?,
) {
    if (start == null) return
    val absolute = hostPath(viewModel.cwd, path)
    val uri =
        java.net
            .URI(
                "file",
                "",
                if (absolute.startsWith('/')) absolute else "/$absolute",
                null,
            ).toASCIIString()
    val existing = screenState.mutations.selectedResourceLinks.value
    if (existing.none { it.uri == uri }) {
        screenState.mutations.selectedResourceLinks.value = existing +
            ChatPromptResourceLink(name = path.substringAfterLast('/'), uri = uri)
    }
    screenState.mutations.messageText.edit { replace(start, selection.end, "") }
}
