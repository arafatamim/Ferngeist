package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.OnSwitchSession
import com.tamimarafat.ferngeist.feature.chat.SwitcherUiState
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
            modifier = Modifier.onSizeChanged { queueHeightPx = it.height },
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
                .padding(horizontal = 1.dp),
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
            onAttach = { screenState.actions.attachmentPickerLauncher.launch(arrayOf("*/*")) },
            dropTargetActive = dropTargetActive,
        )
        if (!screenState.mutations.composerExpanded.value) {
            SessionSwitcherBubble(
                count = switcherState.liveTotalCount,
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
