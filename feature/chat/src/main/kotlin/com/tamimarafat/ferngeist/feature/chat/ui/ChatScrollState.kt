package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatScrollSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// region: Public Handle

/**
 * Public handle for the chat scroll system. ChatScreen receives this and
 * distributes its fields to sub-composables.
 */
internal class ChatScrollHandle(
    val listState: LazyListState,
    val userScrollDetector: NestedScrollConnection,
    val onStreamLayoutSettled: () -> Unit,
    val onSendMessage: () -> Unit,
    val jumpToTop: suspend () -> Unit,
    val showJumpToBottom: State<Boolean>,
    val jumpToBottom: suspend () -> Unit,
)
// endregion

// region: Scroll State Composable

/**
 * Creates and remembers the chat scroll system: [LazyListState], [ChatScrollPolicy],
 * NestedScrollConnection, and all LaunchedEffect wiring.
 *
 * ## Owned concerns
 * - Frame-synced scroll execution
 * - Decision → scroll translation (cancel-and-restart Job for conflation)
 * - Snapshot persistence (snapshotFlow + debounce + persist callback)
 * - Snapshot restoration (2-pass scrollToItem + markRestored)
 * - Idle timeout loop
 * - Manual-bottom-resume observer
 * - Insets-change observer
 *
 * @param sessionId stable identifier for snapshot keying
 * @param renderedMessages current message list (used for snapshot anchor lookups)
 * @param composerContentHeightPx composer bar height in pixels
 * @param imeBottomPx IME inset bottom in pixels
 * @param activelyStreaming true when a streaming response is in progress
 * @param restoredScrollSnapshot snapshot to restore from (null = fresh session)
 * @param restoreReady true when messages are loaded and ready for scroll restoration
 * @param onScrollSnapshotChanged callback to persist snapshots
 */
@Composable
internal fun rememberChatScrollState(
    sessionId: String,
    renderedMessages: List<ChatMessage>,
    composerContentHeightPx: Int,
    imeBottomPx: Int,
    activelyStreaming: Boolean,
    restoredScrollSnapshot: ChatScrollSnapshot?,
    restoreReady: Boolean,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
): ChatScrollHandle {
    val listState = remember(sessionId) { LazyListState() }
    val policy = remember(sessionId) { ChatScrollPolicy() }
    val scope = rememberCoroutineScope()

    val showJumpToBottom = remember { mutableStateOf(false) }

    var isFollowingState by remember { mutableStateOf(true) }

    // -- scroll execution with cancel-and-restart conflation -------------------
    var programmaticScrolling by remember { mutableStateOf(false) }

    // wrapped in mutableStateOf because executeDecision captures this
    // reference; a plain var captured by a lambda wouldn't stay updatable
    // across coroutine launches (the lambda captures the initial value).
    val scrollJob = remember { mutableStateOf<Job?>(null) }

    val executeDecision: (ScrollDecision) -> Unit = executeDecision@{ decision ->
        isFollowingState = policy.isFollowing

        // None: nothing to do, but we synced isFollowingState above.
        if (decision is ScrollDecision.None) return@executeDecision

        // CancelPending: kill current job (e.g. user scrolled away before a
        // DelayedFollow fired) but don't start a new scroll.
        if (decision is ScrollDecision.CancelPending) {
            scrollJob.value?.cancel()
            scrollJob.value = null
            return@executeDecision
        }

        // All remaining decisions start a new scroll — cancel the old one
        // so only the latest request survives (conflation).
        scrollJob.value?.cancel()
        scrollJob.value =
            when (decision) {
                is ScrollDecision.SnapToBottom -> {
                    scope.launch {
                        programmaticScrolling = true
                        try {
                            listState.scrollToBottom()
                        } finally {
                            programmaticScrolling = false
                        }
                    }
                }
                is ScrollDecision.DelayedFollow -> {
                    scope.launch {
                        delay(decision.delayMs)
                        programmaticScrolling = true
                        try {
                            listState.scrollToBottom()
                        } finally {
                            programmaticScrolling = false
                        }
                    }
                }
                is ScrollDecision.SendFollow -> {
                    scope.launch {
                        programmaticScrolling = true
                        try {
                            repeat(AutoScrollConfig.SEND_FOLLOW_PASSES) {
                                listState.scrollToBottom()
                                delay(AutoScrollConfig.SEND_FOLLOW_DELAY_MS)
                            }
                        } finally {
                            programmaticScrolling = false
                        }
                    }
                }
            }
    }
    val userScrollDetector =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // programmaticScrolling guards against viewmodel-initiated
                    // scrolls (scrollToItem, scrollBy) that don't carry
                    // NestedScrollSource.UserInput. Without this flag, every
                    // auto-scroll would be misinterpreted as a user gesture
                    // and would pause following.
                    if (
                        !programmaticScrolling &&
                        source == NestedScrollSource.UserInput &&
                        kotlin.math.abs(available.y) > 0.5f
                    ) {
                        val decision = policy.onUserScrolled()
                        executeDecision(decision)
                    }
                    return Offset.Zero
                }
            }
        }

    // -- restore pending flag --------------------------------------------------
    var restorePending by
        remember(sessionId, restoredScrollSnapshot?.savedAt) {
            mutableStateOf(restoredScrollSnapshot != null)
        }

    // == LaunchedEffects =======================================================

    // 1. Idle timeout observer - uses snapshotFlow instead of polling loop
    LaunchedEffect(policy, listState) {
        snapshotFlow {
            listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX)
        }.distinctUntilChanged()
            .filter { it }
            .collect { atBottom ->
                val decision = policy.onIdleTimeout(atBottom)
                executeDecision(decision)
            }
    }

    // 2. Manual bottom resume observer
    LaunchedEffect(policy, activelyStreaming) {
        snapshotFlow { listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX) }
            .distinctUntilChanged()
            .collect {
                val atBottom = listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX)
                val decision = policy.checkManualBottomResume(atBottom, activelyStreaming)
                executeDecision(decision)
            }
    }

    // 3. Composer/IME insets change handler
    LaunchedEffect(
        composerContentHeightPx,
        imeBottomPx,
        renderedMessages.size,
        isFollowingState,
        restorePending,
    ) {
        if (!restorePending) {
            val decision = policy.onInsetsChanged(renderedMessages.size)
            executeDecision(decision)
        }
    }

    // 4. Scroll snapshot persistence
    // Debounce: avoids writing snapshots on every single-frame scroll delta
    // (which would be hundreds of writes per second during a fling). 250ms
    // is long enough to coalesce rapid movement but short enough to capture
    // the user's final position before they navigate away.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(listState, renderedMessages, isFollowingState, sessionId) {
        snapshotFlow {
            if (renderedMessages.isEmpty()) {
                null
            } else {
                val idx = listState.firstVisibleItemIndex
                ScrollObservation(
                    anchorMessageId = renderedMessages.getOrNull(idx)?.id,
                    firstVisibleItemIndex = idx,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    isFollowing = policy.isFollowing,
                )
            }
        }.distinctUntilChanged()
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
                    ),
                )
            }
    }

    // 5. Snapshot restoration (2-pass)
    LaunchedEffect(
        restorePending,
        restoreReady,
        renderedMessages,
        restoredScrollSnapshot?.savedAt,
        composerContentHeightPx,
        imeBottomPx,
    ) {
        if (!restorePending || !restoreReady || renderedMessages.isEmpty()) return@LaunchedEffect
        val snapshot =
            restoredScrollSnapshot
                ?: run {
                    restorePending = false
                    return@LaunchedEffect
                }
        val restoredIndex =
            snapshot.anchorMessageId
                ?.let { anchorId -> renderedMessages.indexOfFirst { it.id == anchorId } }
                ?.takeIf { it >= 0 }
                ?: snapshot.firstVisibleItemIndex.coerceIn(0, renderedMessages.lastIndex)

        // Two-pass: the first scrollToItem positions the index, but layout
        // may not have settled yet. The second pass corrects any offset drift.
        repeat(2) {
            withFrameNanos { }
            listState.scrollToItem(
                index = restoredIndex,
                scrollOffset = snapshot.firstVisibleItemScrollOffset.coerceAtLeast(0),
            )
        }
        val decision = policy.markRestored(snapshot.isFollowing)
        restorePending = false
        executeDecision(decision)
    }

    // 6. Reactively show/hide the jump-to-bottom FAB.
    // Show only when there are messages AND auto-follow is paused.
    LaunchedEffect(policy, renderedMessages.size) {
        snapshotFlow {
            renderedMessages.isNotEmpty() && !isFollowingState
        }.distinctUntilChanged()
            .collect { show -> showJumpToBottom.value = show }
    }

    // -- callbacks exposed on the handle ---------------------------------------
    val onStreamLayoutSettled: () -> Unit = {
        val decision = policy.onStreamingBubbleResized()
        executeDecision(decision)
    }

    val onSendMessage: () -> Unit = {
        val decision = policy.requestScrollToBottomForSend()
        executeDecision(decision)
    }

    val jumpToTop: suspend () -> Unit = {
        // Tapping "scroll to top" is a deliberate move away from the bottom.
        // Route it through the same transition as a manual scroll so the policy
        // leaves Following and cancels any pending follow job. Otherwise the
        // Following-state mechanisms (streaming-bubble resize, insets follow)
        // immediately scroll back to the bottom and fight this jump.
        executeDecision(policy.onUserScrolled())
        programmaticScrolling = true
        try {
            listState.animateScrollToItem(0)
        } finally {
            programmaticScrolling = false
        }
    }

    val jumpToBottom: suspend () -> Unit = {
        // Resume following without firing the multi-pass send follow.
        // The user-initiated jump should be a single smooth animation, not
        // the 3-pass scroll-to-bottom that sending a message uses.
        executeDecision(policy.resumeFollowing())
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    return ChatScrollHandle(
        listState = listState,
        userScrollDetector = userScrollDetector,
        onStreamLayoutSettled = onStreamLayoutSettled,
        onSendMessage = onSendMessage,
        jumpToTop = jumpToTop,
        showJumpToBottom = showJumpToBottom,
        jumpToBottom = jumpToBottom,
    )
}
// endregion

// region: Scroll Observation

/** Captures scroll state for restoration when navigating back to a chat. */
private data class ScrollObservation(
    val anchorMessageId: String?,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val isFollowing: Boolean,
)
// endregion

// region: Scroll Helpers

/**
 * Checks if the list is scrolled to the bottom within a tolerance.
 *
 * @param tolerancePx Pixels of overflow allowed before considering "not at bottom"
 */
internal fun LazyListState.isAtBottom(tolerancePx: Int = 2): Boolean {
    val layoutInfo = this.layoutInfo
    val total = layoutInfo.totalItemsCount
    if (total == 0) return true
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    if (lastVisible.index != total - 1) return false
    val itemBottom = lastVisible.offset + lastVisible.size
    return itemBottom <= layoutInfo.viewportEndOffset + tolerancePx
}

/**
 * Frame-synced scroll-to-bottom. Waits for next frame, fast-paths if already
 * at bottom, scrolls to last item, waits for layout, then scrolls remaining
 * overflow with a bounded correction loop (capped at
 * [AutoScrollConfig.MAX_SCROLL_BY_PX] per step).
 */
internal suspend fun LazyListState.scrollToBottom() {
    withFrameNanos { }
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    // Fast-path: if already within tolerance, skip the scroll entirely.
    // Avoids unnecessary layout passes during streaming.
    if (isAtBottom(AutoScrollConfig.FOLLOW_TOLERANCE_PX)) return

    scrollToItem(lastIndex)
    withFrameNanos { }

    // Bounded loop: a single scrollBy capped at 720px may not cover
    // large composer/IME gaps. Each iteration waits a frame, re-measures the
    // remaining overflow, and scrolls again if needed.
    repeat(AutoScrollConfig.SCROLL_CORRECTION_MAX_PASSES) {
        val info = layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
        val overflow = (lastVisible.offset + lastVisible.size) - info.viewportEndOffset
        if (overflow <= AutoScrollConfig.FOLLOW_TOLERANCE_PX) return
        val delta = overflow.coerceAtMost(AutoScrollConfig.MAX_SCROLL_BY_PX)
        scrollBy(delta.toFloat())
        if (it < AutoScrollConfig.SCROLL_CORRECTION_MAX_PASSES - 1) withFrameNanos { }
    }
}
// endregion
