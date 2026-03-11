package com.tamimarafat.ferngeist.feature.chat.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.core.common.ui.SessionSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.SessionTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.connectionStateLabel
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatScrollSnapshot
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.ui.AutoScrollConfig.COMPOSER_FOLLOW_SETTLE_MS
import com.tamimarafat.ferngeist.feature.chat.ui.AutoScrollConfig.FOLLOW_TOLERANCE_PX
import com.tamimarafat.ferngeist.feature.chat.ui.AutoScrollConfig.INITIAL_FOLLOW_SETTLE_MS
import com.tamimarafat.ferngeist.feature.chat.ui.AutoScrollConfig.RESUME_TOLERANCE_PX
import com.tamimarafat.ferngeist.feature.chat.ui.AutoScrollConfig.STREAM_FOLLOW_TICK_MS
import com.tamimarafat.ferngeist.feature.chat.ui.AutoScrollConfig.USER_RESUME_IDLE_MS
import com.tamimarafat.ferngeist.feature.chat.ui.AutoScrollConfig.USER_SCROLL_SIGNAL_WINDOW_MS
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Locale

// region: Auto-Scroll Configuration
/**
 * Configuration constants for the auto-scroll mechanism.
 *
 * ## Design Tradeoffs
 * - [INITIAL_FOLLOW_SETTLE_MS] is longer (420ms) to allow initial content to fully render
 *   before scrolling, preventing jarring jumps during first load.
 * - [COMPOSER_FOLLOW_SETTLE_MS] is shorter (120ms) because keyboard animations are
 *   already smooth and users expect quick response when typing.
 * - [STREAM_FOLLOW_TICK_MS] (140ms) balances smoothness vs. performance during streaming.
 * - [USER_SCROLL_SIGNAL_WINDOW_MS] (220ms) filters out accidental touch noise.
 * - [USER_RESUME_IDLE_MS] (320ms) gives users time to read before auto-resuming.
 * - [FOLLOW_TOLERANCE_PX] (24px) allows "close enough" positioning to avoid micro-scrolls.
 * - [RESUME_TOLERANCE_PX] (48px) is more lenient for auto-resume to feel natural.
 */
private object AutoScrollConfig {
    const val INITIAL_FOLLOW_SETTLE_MS = 420L
    const val COMPOSER_FOLLOW_SETTLE_MS = 120L
    const val STREAM_FOLLOW_TICK_MS = 140L
    const val USER_SCROLL_SIGNAL_WINDOW_MS = 220L
    const val USER_RESUME_IDLE_MS = 320L
    const val MAX_SCROLL_BY_PX = 720
    const val FOLLOW_TOLERANCE_PX = 24
    const val RESUME_TOLERANCE_PX = 48
    const val FOLLOW_CORRECTION_PASSES = 4
    const val FOLLOW_CORRECTION_DELAY_MS = 16L
    const val SEND_FOLLOW_PASSES = 3
    const val SEND_FOLLOW_DELAY_MS = 32L
    const val SCROLL_DEBOUNCE_MS = 250L
}
// endregion

// region: Data Classes

/**
 * Represents the content state that anchors scroll position calculations.
 * Used to detect when content has changed and scrolling may be needed.
 */
private data class ContentAnchor(
    val messageCount: Int,
    val lastMessageId: String?,
    val lastMessageContentLength: Int,
    val lastMessageSegmentCount: Int,
    val lastToolOutputLength: Int,
    val isStreaming: Boolean,
)

/**
 * Captures scroll state for restoration when navigating back to a chat.
 */
private data class ScrollObservation(
    val anchorMessageId: String?,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val isFollowing: Boolean,
)
// endregion

// region: Auto-Scroll State Machine

/**
 * Sealed class representing the auto-scroll state machine.
 *
 * ## State Transitions
 * ```
 *                    ┌─────────────────────────────────────┐
 *                    │                                     │
 *                    ▼                                     │
 *   [Initial] → [Following] ←──────────────────────┐      │
 *                    │                              │      │
 *                    │ User scrolls                 │      │
 *                    ▼                              │      │
 *              [PausedByUser] ──────────────────────┘      │
 *                    │                              │      │
 *                    │ At bottom + streaming        │      │
 *                    │ OR idle timeout              │      │
 *                    └──────────────────────────────┘      │
 * ```
 *
 * ## Responsibilities
 * - [Following]: Auto-scroll to bottom on content changes
 * - [PausedByUser]: Respect user's manual scroll position
 */
private sealed class AutoScrollState {
    /** Auto-scroll is active; scroll to bottom on content changes */
    data object Following : AutoScrollState()

    /** User manually scrolled; auto-scroll paused until resume conditions are met */
    data object PausedByUser : AutoScrollState()
}

/**
 * Events that can trigger state transitions in the [AutoScrollManager].
 */
private sealed class AutoScrollEvent {
    /** User performed a scroll gesture */
    data object UserScrolled : AutoScrollEvent()

    /** Content has changed (new message, streaming update, etc.) */
    data class ContentChanged(val anchor: ContentAnchor) : AutoScrollEvent()

    /** Keyboard or composer height changed */
    data class InsetsChanged(val messageCount: Int) : AutoScrollEvent()

    /** Streaming state changed */
    data class StreamingChanged(val isStreaming: Boolean) : AutoScrollEvent()

    /** Scroll animation completed */
    data object ScrollCompleted : AutoScrollEvent()

    /** Idle timeout expired; check if we should resume */
    data object IdleTimeout : AutoScrollEvent()
}

/**
 * Manages auto-scroll behavior for the chat message list.
 *
 * ## Architecture
 * This class implements a state machine pattern to manage scroll behavior:
 * 1. Events flow in through [onEvent]
 * 2. State transitions are computed based on current state + event
 * 3. Side effects (scrolling) are emitted through [sideEffectFlow]
 *
 * ## Testability
 * Time-dependent operations use [currentTimeMs] which can be overridden in tests.
 *
 * @param listState The LazyListState to control
 * @param currentTimeMs Function to get current time in milliseconds (for testability)
 */
private class AutoScrollManager(
    private val listState: LazyListState,
    private val currentTimeMs: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val _state = MutableStateFlow<AutoScrollState>(AutoScrollState.Following)

    private val _sideEffects = Channel<ScrollSideEffect>(Channel.BUFFERED)
    val sideEffectFlow: Flow<ScrollSideEffect> = _sideEffects.receiveAsFlow()

    private var lastUserScrollTimeMs = 0L
    private var isProgrammaticScrolling = false
    private var hasHandledInitialFollow = false
    private var skipNextInsetsFollow = false
    private var lastHandledAnchor: ContentAnchor? = null

    val isFollowing: Boolean
        get() = _state.value is AutoScrollState.Following

    /**
     * Creates a NestedScrollConnection that detects user scrolls.
     * Instead of mutating state directly, it emits events.
     */
    fun createUserScrollDetector(): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && kotlin.math.abs(available.y) > 0.5f) {
                onEvent(AutoScrollEvent.UserScrolled)
            }
            return Offset.Zero
        }
    }

    /**
     * Processes an event and triggers appropriate state transitions and side effects.
     */
    fun onEvent(event: AutoScrollEvent) {
        when (event) {
            is AutoScrollEvent.UserScrolled -> handleUserScroll()
            is AutoScrollEvent.ContentChanged -> handleContentChanged(event.anchor)
            is AutoScrollEvent.InsetsChanged -> handleInsetsChanged(event.messageCount)
            is AutoScrollEvent.StreamingChanged -> handleStreamingChanged(event.isStreaming)
            is AutoScrollEvent.ScrollCompleted -> handleScrollCompleted()
            is AutoScrollEvent.IdleTimeout -> handleIdleTimeout()
        }
    }

    private fun handleUserScroll() {
        lastUserScrollTimeMs = currentTimeMs()
        if (!isProgrammaticScrolling && _state.value is AutoScrollState.Following) {
            _state.value = AutoScrollState.PausedByUser
        }
    }

    private fun handleContentChanged(anchor: ContentAnchor) {
        if (lastHandledAnchor == anchor) return
        lastHandledAnchor = anchor

        if (_state.value !is AutoScrollState.Following || anchor.messageCount == 0) return

        if (!hasHandledInitialFollow) {
            hasHandledInitialFollow = true
            emitSideEffect(ScrollSideEffect.DelayThenScroll(INITIAL_FOLLOW_SETTLE_MS))
        } else {
            emitSideEffect(ScrollSideEffect.ScrollToBottom(smooth = true))
        }
    }

    private fun handleInsetsChanged(messageCount: Int) {
        if (skipNextInsetsFollow) {
            skipNextInsetsFollow = false
            return
        }
        if (_state.value !is AutoScrollState.Following || messageCount == 0) return

        emitSideEffect(ScrollSideEffect.DelayThenScroll(COMPOSER_FOLLOW_SETTLE_MS))
    }

    private fun handleStreamingChanged(isStreaming: Boolean) {
        // State is handled by the flow collector in rememberChatScrollState
    }

    private fun handleScrollCompleted() {
        isProgrammaticScrolling = false
    }

    private fun handleIdleTimeout() {
        val now = currentTimeMs()
        val timeSinceLastScroll = now - lastUserScrollTimeMs
        val recentlyUserScrolled = timeSinceLastScroll <= USER_SCROLL_SIGNAL_WINDOW_MS

        if (_state.value is AutoScrollState.PausedByUser &&
            !recentlyUserScrolled &&
            timeSinceLastScroll >= USER_RESUME_IDLE_MS &&
            listState.isAtBottom(RESUME_TOLERANCE_PX)
        ) {
            _state.value = AutoScrollState.Following
            emitSideEffect(ScrollSideEffect.ScrollToBottom(smooth = false))
        }
    }

    /**
     * Checks if manual scroll to bottom should resume auto-scroll.
     */
    fun checkManualBottomResume(isStreaming: Boolean) {
        if (listState.isAtBottom(RESUME_TOLERANCE_PX) &&
            isStreaming &&
            _state.value is AutoScrollState.PausedByUser
        ) {
            _state.value = AutoScrollState.Following
            emitSideEffect(ScrollSideEffect.ScrollToBottom(smooth = false))
        }
    }

    /**
     * Marks the scroll state as restored from a snapshot.
     */
    fun markRestored(anchor: ContentAnchor) {
        lastHandledAnchor = anchor
        skipNextInsetsFollow = true
    }

    /**
     * Resumes following mode (e.g., after user sends a message).
     */
    fun resumeFollowing() {
        _state.value = AutoScrollState.Following
    }

    private fun emitSideEffect(effect: ScrollSideEffect) {
        kotlinx.coroutines.runBlocking {
            _sideEffects.send(effect)
        }
    }

    // region: Scroll Operations

    /**
     * Scrolls to bottom for a send operation with aggressive correction.
     */
    suspend fun scrollToBottomForSend() {
        repeat(AutoScrollConfig.SEND_FOLLOW_PASSES) {
            scrollToBottom(smooth = true)
            delay(AutoScrollConfig.SEND_FOLLOW_DELAY_MS)
        }
    }

    /**
     * Continuously follows streaming content with periodic scroll corrections.
     * Uses Flow-based approach for proper cancellation handling.
     */
    fun followWhileStreamingFlow(isStreaming: Boolean): Flow<Unit> = flow<Unit> {
        if (!isStreaming || _state.value !is AutoScrollState.Following) return@flow

        while (true) {
            scrollToBottom(smooth = true)
            delay(STREAM_FOLLOW_TICK_MS)
        }
    }.catch {
        // Silently handle cancellation - this is expected when streaming stops
    }

    /**
     * Scrolls to the bottom of the list with multi-pass correction.
     *
     * ## Algorithm
     * 1. Scroll to last item
     * 2. Check if overflow exceeds tolerance
     * 3. If yes, scroll by the overflow amount (capped at MAX)
     * 4. Repeat for N passes to handle layout changes
     * 5. Final verification pass
     */
    suspend fun scrollToBottom(smooth: Boolean = false) {
        if (_state.value !is AutoScrollState.Following) return

        isProgrammaticScrolling = true
        try {
            var pass = 0
            while (pass < AutoScrollConfig.FOLLOW_CORRECTION_PASSES) {
                val info = listState.layoutInfo
                val lastIndex = info.totalItemsCount - 1
                if (lastIndex < 0) break

                val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }
                if (lastVisible == null) {
                    if (smooth) {
                        listState.animateScrollToItem(lastIndex)
                    } else {
                        listState.scrollToItem(lastIndex)
                    }
                } else {
                    val overflow = (lastVisible.offset + lastVisible.size) - info.viewportEndOffset
                    if (overflow <= FOLLOW_TOLERANCE_PX) {
                        break
                    }
                    val delta = overflow.coerceAtMost(AutoScrollConfig.MAX_SCROLL_BY_PX)
                    if (smooth) {
                        listState.animateScrollBy(delta.toFloat())
                    } else {
                        listState.scrollBy(delta.toFloat())
                    }
                }

                pass += 1
                if (pass < AutoScrollConfig.FOLLOW_CORRECTION_PASSES) {
                    delay(AutoScrollConfig.FOLLOW_CORRECTION_DELAY_MS)
                }
            }

            // Final verification pass
            val finalInfo = listState.layoutInfo
            val finalLastIndex = finalInfo.totalItemsCount - 1
            if (finalLastIndex >= 0 && !listState.isAtBottom(FOLLOW_TOLERANCE_PX)) {
                val finalLastVisible =
                    finalInfo.visibleItemsInfo.lastOrNull { it.index == finalLastIndex }
                if (finalLastVisible == null) {
                    if (smooth) {
                        listState.animateScrollToItem(finalLastIndex)
                    } else {
                        listState.scrollToItem(finalLastIndex)
                    }
                } else {
                    val overflow =
                        (finalLastVisible.offset + finalLastVisible.size) - finalInfo.viewportEndOffset
                    if (overflow > FOLLOW_TOLERANCE_PX) {
                        if (smooth) {
                            listState.animateScrollBy(overflow.toFloat())
                        } else {
                            listState.scrollBy(overflow.toFloat())
                        }
                    }
                }
            }
        } finally {
            isProgrammaticScrolling = false
        }
    }
    // endregion
}

/**
 * Side effects emitted by [AutoScrollManager] that require coroutine scope.
 */
private sealed class ScrollSideEffect {
    /** Scroll to bottom immediately */
    data class ScrollToBottom(val smooth: Boolean) : ScrollSideEffect()

    /** Wait for delay, then scroll to bottom */
    data class DelayThenScroll(val delayMs: Long) : ScrollSideEffect()
}
// endregion

// region: Scroll State Helper

/**
 * Checks if the list is scrolled to the bottom within a tolerance.
 *
 * @param tolerancePx Pixels of overflow allowed before considering "not at bottom"
 */
private fun LazyListState.isAtBottom(tolerancePx: Int = 2): Boolean {
    val layoutInfo = this.layoutInfo
    val total = layoutInfo.totalItemsCount
    if (total == 0) return true
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    if (lastVisible.index != total - 1) return false
    val itemBottom = lastVisible.offset + lastVisible.size
    return itemBottom <= layoutInfo.viewportEndOffset + tolerancePx
}
// endregion

// region: Remember Composable

@Composable
private fun rememberChatScrollState(
    sessionId: String,
    renderedMessages: List<ChatMessage>,
    contentAnchor: ContentAnchor,
    composerContentHeightPx: Int,
    imeBottomPx: Int,
    activelyStreaming: Boolean,
    restoredScrollSnapshot: ChatScrollSnapshot?,
    restoreReady: Boolean,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
): Pair<LazyListState, AutoScrollManager> {
    val listState = rememberLazyListState()
    val scrollManager = remember(listState) { AutoScrollManager(listState) }

    var restorePending by remember(sessionId, restoredScrollSnapshot?.savedAt) {
        mutableStateOf(restoredScrollSnapshot != null)
    }

    // Handle side effects from the scroll manager
    LaunchedEffect(scrollManager) {
        scrollManager.sideEffectFlow.collect { effect ->
            when (effect) {
                is ScrollSideEffect.ScrollToBottom -> {
                    scrollManager.scrollToBottom(smooth = effect.smooth)
                }

                is ScrollSideEffect.DelayThenScroll -> {
                    delay(effect.delayMs)
                    scrollManager.scrollToBottom(smooth = true)
                }
            }
        }
    }

    // Idle timeout observer
    LaunchedEffect(scrollManager) {
        while (true) {
            delay(USER_RESUME_IDLE_MS)
            scrollManager.onEvent(AutoScrollEvent.IdleTimeout)
        }
    }

    // Manual bottom resume observer
    LaunchedEffect(scrollManager, activelyStreaming) {
        snapshotFlow { listState.isAtBottom(RESUME_TOLERANCE_PX) }
            .distinctUntilChanged()
            .collect { _ ->
                scrollManager.checkManualBottomResume(activelyStreaming)
            }
    }

    // Streaming follow with proper cancellation
    LaunchedEffect(scrollManager, activelyStreaming, scrollManager.isFollowing, restorePending) {
        if (!restorePending) {
            scrollManager.followWhileStreamingFlow(activelyStreaming).collect { }
        }
    }

    // Content anchor changes
    LaunchedEffect(contentAnchor, scrollManager.isFollowing, restorePending) {
        if (!restorePending) {
            scrollManager.onEvent(AutoScrollEvent.ContentChanged(contentAnchor))
        }
    }

    // Composer insets changes
    LaunchedEffect(
        composerContentHeightPx,
        imeBottomPx,
        contentAnchor.messageCount,
        scrollManager.isFollowing,
        restorePending,
    ) {
        if (!restorePending) {
            scrollManager.onEvent(AutoScrollEvent.InsetsChanged(contentAnchor.messageCount))
        }
    }

    // Scroll snapshot persistence
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(listState, renderedMessages, scrollManager.isFollowing, sessionId) {
        snapshotFlow {
            if (renderedMessages.isEmpty()) {
                null
            } else {
                val firstVisibleItemIndex = listState.firstVisibleItemIndex
                ScrollObservation(
                    anchorMessageId = renderedMessages.getOrNull(firstVisibleItemIndex)?.id,
                    firstVisibleItemIndex = firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    isFollowing = scrollManager.isFollowing,
                )
            }
        }
            .distinctUntilChanged()
            .debounce(AutoScrollConfig.SCROLL_DEBOUNCE_MS)
            .collect { observation ->
                observation ?: return@collect
                onScrollSnapshotChanged(
                    ChatScrollSnapshot(
                        anchorMessageId = observation.anchorMessageId,
                        firstVisibleItemIndex = observation.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = observation.firstVisibleItemScrollOffset,
                        isFollowing = observation.isFollowing,
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
    }

    // Restore from snapshot
    LaunchedEffect(
        restorePending,
        restoreReady,
        renderedMessages,
        restoredScrollSnapshot?.savedAt,
        composerContentHeightPx,
        imeBottomPx,
    ) {
        if (!restorePending || !restoreReady || renderedMessages.isEmpty()) return@LaunchedEffect
        val snapshot = restoredScrollSnapshot ?: run {
            restorePending = false
            return@LaunchedEffect
        }
        val restoredIndex = snapshot.anchorMessageId
            ?.let { anchorId -> renderedMessages.indexOfFirst { it.id == anchorId } }
            ?.takeIf { it >= 0 }
            ?: snapshot.firstVisibleItemIndex.coerceIn(0, renderedMessages.lastIndex)

        repeat(2) {
            withFrameNanos { }
            listState.scrollToItem(
                index = restoredIndex,
                scrollOffset = snapshot.firstVisibleItemScrollOffset.coerceAtLeast(0),
            )
        }
        scrollManager.markRestored(contentAnchor)
        restorePending = false
    }

    return listState to scrollManager
}
// endregion

// region: Main ChatScreen Composable

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun ChatScreen(
    sessionId: String,
    sessionTitle: String,
    onNavigateBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showModelPicker by remember { mutableStateOf(false) }
    var showCommandsDialog by remember { mutableStateOf(false) }
    var showConnectionStatusDialog by remember { mutableStateOf(false) }
    var composerContentHeightPx by remember { mutableIntStateOf(0) }
    var messageText by remember { mutableStateOf("") }
    var composerExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val systemBottomInsetPx = if (imeBottomPx > navBottomPx) imeBottomPx else navBottomPx
    val showComposerToolbar = !state.isLoading && !(state.error != null && state.messages.isEmpty())
    val composerContentHeightDp = with(density) { composerContentHeightPx.toDp() }
    val screenWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val systemBottomInsetDp = with(density) { systemBottomInsetPx.toDp() }
    val listBottomPadding = if (!showComposerToolbar) {
        0.dp
    } else {
        composerContentHeightDp +
                systemBottomInsetDp +
                FloatingToolbarDefaults.ScreenOffset +
                36.dp
    }
    val snackbarBottomPadding = if (!showComposerToolbar) {
        0.dp
    } else {
        composerContentHeightDp +
                systemBottomInsetDp +
                FloatingToolbarDefaults.ScreenOffset +
                16.dp
    }
    val modelOption = remember(state.configOptions) {
        state.configOptions.firstOrNull { it.id == "model" && it.options.isNotEmpty() }
    }
    val activeModel = remember(state.configOptions) {
        val currentModelOption = state.configOptions.firstOrNull { it.id == "model" }
        currentModelOption?.let { option ->
            option.options.firstOrNull { it.value == option.currentValue }?.label
                ?: option.currentValue
        }
    }
    val canCancelStreaming = state.canCancelStreaming
    val hasStreamingBubble = state.messages.lastOrNull()?.isStreaming == true
    val activelyStreaming = state.isStreaming || hasStreamingBubble
    val showStopAction = state.isStreaming && hasStreamingBubble
    val showModeButton = state.availableModes.isNotEmpty() || !state.currentModeId.isNullOrBlank()
    val currentModeLabel = remember(state.availableModes, state.currentModeId) {
        state.availableModes.firstOrNull { it.id == state.currentModeId }?.name?.uppercase(Locale.getDefault())
            ?: state.currentModeId?.uppercase(Locale.getDefault())
            ?: "MODE"
    }
    val renderedMessages = state.messages
    val renderedLastMessageId = renderedMessages.lastOrNull()?.id
    val contentAnchor = remember(renderedMessages, state.isStreaming) {
        val last = renderedMessages.lastOrNull()
        val lastToolOutputLength = last?.segments
            ?.lastOrNull { it.toolCall != null }
            ?.toolCall
            ?.output
            ?.length ?: 0
        ContentAnchor(
            messageCount = renderedMessages.size,
            lastMessageId = last?.id,
            lastMessageContentLength = last?.content?.length ?: 0,
            lastMessageSegmentCount = last?.segments?.size ?: 0,
            lastToolOutputLength = lastToolOutputLength,
            isStreaming = state.isStreaming,
        )
    }
    val (listState, scrollManager) = rememberChatScrollState(
        sessionId = sessionId,
        renderedMessages = renderedMessages,
        contentAnchor = contentAnchor,
        composerContentHeightPx = composerContentHeightPx,
        imeBottomPx = imeBottomPx,
        activelyStreaming = activelyStreaming,
        restoredScrollSnapshot = state.restoredScrollSnapshot,
        restoreReady = renderedMessages.isNotEmpty() && !state.isLoading,
        onScrollSnapshotChanged = viewModel::persistScrollSnapshot,
    )

    // --- Composer spring animations ---
    val fadeSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val buttonsAlpha by animateFloatAsState(
        targetValue = if (composerExpanded) 0f else 1f,
        animationSpec = fadeSpring,
        label = "ButtonsAlpha"
    )
    val inputAlpha by animateFloatAsState(
        targetValue = if (composerExpanded) 1f else 0f,
        animationSpec = fadeSpring,
        label = "InputAlpha"
    )

    val sendMessage: () -> Unit = {
        if (messageText.isNotBlank()) {
            viewModel.dispatch(ChatIntent.SendMessage(messageText))
            scrollManager.resumeFollowing()
            coroutineScope.launch {
                scrollManager.scrollToBottomForSend()
            }
            messageText = ""
            composerExpanded = false
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.ShowError -> {
                    android.util.Log.e("ChatScreen", "Chat effect error: ${effect.message}")
                    snackbarHostState.showSnackbar(effect.message)
                }

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(effect.message)
                }

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.NavigateBack -> onNavigateBack()
            }
        }
    }

    // Auto-focus the text field when the composer expands.
    LaunchedEffect(composerExpanded) {
        if (composerExpanded) {
            focusRequester.requestFocus()
        }
    }
    with(sharedTransitionScope) {
        Box(
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(
                        key = SessionSharedBoundsKey(sessionId),
                    ),
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                )
                .fillMaxSize()
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    ChatTopBar(
                        sessionId = sessionId,
                        sessionTitle = sessionTitle,
                        activeModel = activeModel,
                        connectionState = state.connectionState,
                        onNavigateBack = onNavigateBack,
                        onConnectionStatusClick = { showConnectionStatusDialog = true },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    ChatScreenDialogs(
                        showModelPicker = showModelPicker,
                        modelOption = modelOption,
                        onModelSelected = { value ->
                            viewModel.dispatch(ChatIntent.SetConfigOption("model", value))
                            showModelPicker = false
                        },
                        onDismissModelPicker = { showModelPicker = false },
                        showConnectionStatusDialog = showConnectionStatusDialog,
                        connectionState = state.connectionState,
                        diagnostics = state.connectionDiagnostics,
                        usage = state.usage,
                        onDismissConnectionStatus = { showConnectionStatusDialog = false },
                        showCommandsDialog = showCommandsDialog,
                        commands = state.availableCommands,
                        onDismissCommands = { showCommandsDialog = false },
                        onCommandClick = { command ->
                            showCommandsDialog = false
                            val normalized = command.trim()
                            val slashCommand =
                                if (normalized.startsWith("/")) normalized else "/$normalized"
                            viewModel.dispatch(ChatIntent.SendMessage(slashCommand))
                        },
                    )

                    ChatScreenBody(
                        state = state,
                        listState = listState,
                        userScrollDetector = scrollManager.createUserScrollDetector(),
                        renderedLastMessageId = renderedLastMessageId,
                        listBottomPadding = listBottomPadding,
                        onRetryLoad = { viewModel.dispatch(ChatIntent.RetryLoad) },
                        onToggleToolCall = { toolCallId ->
                            viewModel.dispatch(ChatIntent.ToggleToolCallExpansion(toolCallId))
                        },
                        onGrantPermission = { toolCallId, optionId ->
                            viewModel.dispatch(ChatIntent.GrantPermission(toolCallId, optionId))
                        },
                        onDenyPermission = { toolCallId ->
                            viewModel.dispatch(ChatIntent.DenyPermission(toolCallId))
                        },
                    )

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = snackbarBottomPadding)
                            .zIndex(2f)
                    )

                    if (showComposerToolbar) {
                        ChatComposerBar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 1.dp)
                                .offset(y = -FloatingToolbarDefaults.ScreenOffset)
                                .zIndex(1f),
                            state = state,
                            modelOption = modelOption,
                            composerExpanded = composerExpanded,
                            onComposerExpandedChange = { composerExpanded = it },
                            messageText = messageText,
                            onMessageTextChange = { messageText = it },
                            inputAlpha = inputAlpha,
                            buttonsAlpha = buttonsAlpha,
                            showModeButton = showModeButton,
                            currentModeLabel = currentModeLabel,
                            showStopAction = showStopAction,
                            canCancelStreaming = canCancelStreaming,
                            screenWidth = screenWidthDp,
                            focusRequester = focusRequester,
                            onFocusCleared = { focusManager.clearFocus() },
                            onHeightChanged = { composerContentHeightPx = it },
                            onSend = sendMessage,
                            onCancelStreaming = { viewModel.dispatch(ChatIntent.CancelStreaming) },
                            onSetMode = { modeId -> viewModel.dispatch(ChatIntent.SetMode(modeId)) },
                            onShowCommands = { showCommandsDialog = true },
                            onShowModelPicker = { showModelPicker = true },
                        )
                    }
                }
            }
        }
    }
}
// endregion

// region: Helper Composables

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun ChatTopBar(
    sessionId: String,
    sessionTitle: String,
    activeModel: String?,
    connectionState: AcpConnectionState,
    onNavigateBack: () -> Unit,
    onConnectionStatusClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    TopAppBar(title = {
        Column {
            with(sharedTransitionScope) {
                Text(
                    text = sessionTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = SessionTitleSharedBoundsKey(sessionId),
                        ),
                        animatedVisibilityScope = animatedContentScope,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    ),
                )
            }
            activeModel?.takeIf { it.isNotBlank() }?.let { model ->
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }, navigationIcon = {
        FilledTonalIconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back"
            )
        }
    }, actions = {
        val connectionLabel = connectionStateLabel(connectionState)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text("Connection: $connectionLabel") } },
            state = rememberTooltipState()
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Connection status"
                        stateDescription = connectionLabel
                    }
                    .clickable(onClick = onConnectionStatusClick),
                contentAlignment = Alignment.Center
            ) {
                when (connectionState) {
                    is AcpConnectionState.Connecting -> {
                        LoadingIndicator(
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    is AcpConnectionState.Connected -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.size(12.dp)
                        ) {}
                    }

                    is AcpConnectionState.Failed -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        ) {}
                    }

                    is AcpConnectionState.Disconnected -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(12.dp)
                        ) {}
                    }
                }
            }
        }

    })
}

@Composable
internal fun CommandsDialog(
    commands: List<String>,
    onDismiss: () -> Unit,
    onCommandClick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commands") },
        text = {
            if (commands.isEmpty()) {
                Text(
                    text = "No commands advertised by the server.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    commands.forEach { command ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCommandClick(command) }
                        ) {
                            Text(
                                text = command,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
internal fun ModelPicker(
    modelOption: SessionConfigOption?,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(modelOption) { mutableStateOf("") }
    val filteredOptions = remember(modelOption, query) {
        val options = modelOption?.options.orEmpty()
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            options
        } else {
            options.filter { choice ->
                choice.label.contains(trimmedQuery, ignoreCase = true) || choice.value.contains(
                    trimmedQuery, ignoreCase = true
                ) || (choice.description?.contains(trimmedQuery, ignoreCase = true) == true)
            }
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Select Model") }, text = {
        if (modelOption == null || modelOption.options.isEmpty()) {
            Text(
                text = "No models available from agent.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search models") },
                    placeholder = { Text("Type model name") })

                if (filteredOptions.isEmpty()) {
                    Text(
                        text = "No matching models.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = filteredOptions, key = { it.id }) { choice ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onModelSelected(choice.value) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = choice.value == modelOption.currentValue,
                                    onClick = { onModelSelected(choice.value) })
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = choice.label,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    choice.description?.let { description ->
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = onDismiss) {
            Text("Close")
        }
    })
}
// endregion
