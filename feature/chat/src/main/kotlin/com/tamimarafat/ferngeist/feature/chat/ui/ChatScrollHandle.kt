package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private const val USER_SCROLL_DELTA_THRESHOLD = 0.5f

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
    val isFollowingState = remember { mutableStateOf(true) }
    var restorePending by remember(sessionId, restoredScrollSnapshot?.savedAt) {
        mutableStateOf(restoredScrollSnapshot != null)
    }
    val runner = remember(sessionId, scope, listState, policy, isFollowingState) {
        ChatScrollDecisionRunner(listState, scope, policy, isFollowingState)
    }
    val userScrollDetector = remember(runner) { runner.createUserScrollConnection() }
    val observer = remember(sessionId, listState, policy, runner, onScrollSnapshotChanged) {
        ChatScrollSnapshotObserver(listState, policy, runner, { isFollowingState.value }, onScrollSnapshotChanged)
    }
    LaunchedEffect(policy, listState) { observer.observeIdleTimeout() }
    LaunchedEffect(policy, activelyStreaming) { observer.observeManualBottomResume(activelyStreaming) }
    LaunchedEffect(composerContentHeightPx, imeBottomPx, renderedMessages.size, isFollowingState.value, restorePending) {
        observer.onInsetsChanged(renderedMessages.size, restorePending)
    }
    LaunchedEffect(listState, renderedMessages, isFollowingState.value, sessionId) {
        observer.observePersistence(renderedMessages)
    }
    LaunchedEffect(restorePending, restoreReady, renderedMessages, restoredScrollSnapshot?.savedAt, composerContentHeightPx, imeBottomPx) {
        observer.restoreSnapshot(restoredScrollSnapshot, renderedMessages, restorePending, restoreReady) { restorePending = false }
    }
    LaunchedEffect(policy, renderedMessages.size) {
        observer.observeJumpToBottom(renderedMessages, showJumpToBottom)
    }
    return ChatScrollHandle(
        listState = listState,
        userScrollDetector = userScrollDetector,
        onStreamLayoutSettled = runner::onStreamLayoutSettled,
        onSendMessage = runner::onSendMessage,
        jumpToTop = runner::jumpToTop,
        showJumpToBottom = showJumpToBottom,
        jumpToBottom = runner::jumpToBottom,
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
    // Bounded loop: a single scrollBy capped at MAX_SCROLL_BY_PX may not cover
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

// region: Decision Runner

/**
 * Translates [ScrollDecision] values into concrete scroll actions, with
 * cancel-and-restart Job conflation so only the latest request survives.
 */
private class ChatScrollDecisionRunner(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val policy: ChatScrollPolicy,
    private val isFollowingState: MutableState<Boolean>,
) {
    /** Read by the NestedScrollConnection to filter programmatic scrolls. */
    var programmaticScrolling: Boolean = false

    private var scrollJob: Job? = null

    /**
     * Applies a [ScrollDecision] from the policy:
     * - [ScrollDecision.None]: syncs following state, no scroll.
     * - [ScrollDecision.CancelPending]: cancels any in-flight job.
     * - Otherwise: cancels existing job, launches a new scroll coroutine.
     */
    fun run(decision: ScrollDecision) {
        isFollowingState.value = policy.isFollowing
        if (decision is ScrollDecision.None) return
        if (decision is ScrollDecision.CancelPending) {
            scrollJob?.cancel()
            scrollJob = null
            return
        }
        scrollJob?.cancel()
        scrollJob = scope.launch { executeScroll(decision) }
    }

    /** Launches the coroutine for a concrete scroll decision (SnapToBottom/DelayedFollow/SendFollow). */
    private suspend fun executeScroll(decision: ScrollDecision) {
        programmaticScrolling = true
        try {
            when (decision) {
                is ScrollDecision.SnapToBottom -> listState.scrollToBottom()
                is ScrollDecision.DelayedFollow -> {
                    delay(decision.delayMs)
                    listState.scrollToBottom()
                }
                is ScrollDecision.SendFollow -> {
                    repeat(AutoScrollConfig.SEND_FOLLOW_PASSES) {
                        listState.scrollToBottom()
                        delay(AutoScrollConfig.SEND_FOLLOW_DELAY_MS)
                    }
                }
                is ScrollDecision.None, is ScrollDecision.CancelPending -> Unit
            }
        } finally {
            programmaticScrolling = false
        }
    }

    /** Creates the NestedScrollConnection that detects user scroll gestures. */
    fun createUserScrollConnection(): NestedScrollConnection =
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
                    kotlin.math.abs(available.y) > USER_SCROLL_DELTA_THRESHOLD
                ) {
                    run(policy.onUserScrolled())
                }
                return Offset.Zero
            }
        }

    /** Streaming resize: delegate to policy and apply the decision. */
    fun onStreamLayoutSettled() {
        run(policy.onStreamingBubbleResized())
    }

    /** Send button: delegate to policy and apply the decision. */
    fun onSendMessage() {
        run(policy.requestScrollToBottomForSend())
    }

    /** Scroll to top: route through policy as a user scroll, then animate. */
    suspend fun jumpToTop() {
        // Tapping "scroll to top" is a deliberate move away from the bottom.
        // Route it through the same transition as a manual scroll so the policy
        // leaves Following and cancels any pending follow job. Otherwise the
        // Following-state mechanisms (streaming-bubble resize, insets follow)
        // immediately scroll back to the bottom and fight this jump.
        run(policy.onUserScrolled())
        programmaticScrolling = true
        try {
            listState.animateScrollToItem(0)
        } finally {
            programmaticScrolling = false
        }
    }

    /** Scroll to bottom: resume following without the multi-pass send follow. */
    suspend fun jumpToBottom() {
        // Resume following without firing the multi-pass send follow.
        // The user-initiated jump should be a single smooth animation, not
        // the 3-pass scroll-to-bottom that sending a message uses.
        run(policy.resumeFollowing())
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }
}
// endregion

// region: Snapshot Observer

/**
 * Installs Compose-side observers (idle timeout, manual resume, insets,
 * persistence, jump-to-bottom visibility) and handles snapshot restoration.
 *
 * Captured state accessors are passed as lambdas so the observer reads
 * the latest values on each invocation without re-creating the object.
 */
private class ChatScrollSnapshotObserver(
    private val listState: LazyListState,
    private val policy: ChatScrollPolicy,
    private val runner: ChatScrollDecisionRunner,
    private val isFollowingState: () -> Boolean,
    private val onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
) {
    /**
     * 1. Idle timeout — event-driven. Fires when the list becomes
     * (paused && at bottom), waits out the policy's quiet window, then
     * re-checks once against fresh state.
     *
     * No poll: the only time-based condition (quiet window since the last
     * user scroll) is scheduled on demand from the pause-at-bottom event.
     * If the user scrolls during the wait, the fresh re-check sees the new
     * scroll time and rejects; the next bottom-arrival re-arms it.
     */
    suspend fun observeIdleTimeout() {
        snapshotFlow {
            !policy.isFollowing && listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX)
        }.distinctUntilChanged()
            .filter { it }
            .collect {
                delay(AutoScrollConfig.USER_RESUME_IDLE_MS)
                runner.run(
                    policy.onIdleTimeout(
                        listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX),
                    ),
                )
            }
    }

    /** 2. Manual bottom resume — fires when user drags to bottom mid-stream. */
    suspend fun observeManualBottomResume(activelyStreaming: Boolean) {
        snapshotFlow { listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX) }
            .distinctUntilChanged()
            .collect {
                val atBottom = listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX)
                runner.run(policy.checkManualBottomResume(atBottom, activelyStreaming))
            }
    }

    /** 3. Composer/IME insets change — deferred to insets follow when not restoring. */
    fun onInsetsChanged(messageCount: Int, restorePending: Boolean) {
        if (!restorePending) {
            runner.run(policy.onInsetsChanged(messageCount))
        }
    }

    /** 4. Persistence — snapshotFlow + debounce + persist callback. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    suspend fun observePersistence(renderedMessages: List<ChatMessage>) {
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

    /** 5. Restore scroll position from a persisted snapshot (2-pass). */
    suspend fun restoreSnapshot(
        snapshot: ChatScrollSnapshot?,
        renderedMessages: List<ChatMessage>,
        restorePending: Boolean,
        restoreReady: Boolean,
        onRestoreDone: () -> Unit,
    ) {
        if (!restoreReady || !restorePending || renderedMessages.isEmpty()) return
        val currentSnapshot = snapshot ?: run { onRestoreDone(); return }
        val restoredIndex =
            currentSnapshot.anchorMessageId
                ?.let { anchorId -> renderedMessages.indexOfFirst { it.id == anchorId } }
                ?.takeIf { it >= 0 }
                ?: currentSnapshot.firstVisibleItemIndex.coerceIn(0, renderedMessages.lastIndex)
        // Two-pass: the first scrollToItem positions the index, but layout
        // may not have settled yet. The second pass corrects any offset drift.
        repeat(2) {
            withFrameNanos { }
            listState.scrollToItem(
                index = restoredIndex,
                scrollOffset = currentSnapshot.firstVisibleItemScrollOffset.coerceAtLeast(0),
            )
        }
        val decision = policy.markRestored(currentSnapshot.isFollowing)
        onRestoreDone()
        runner.run(decision)
    }

    /** 6. Show/hide jump-to-bottom FAB: visible when paused AND not at the bottom. */
    suspend fun observeJumpToBottom(
        renderedMessages: List<ChatMessage>,
        showJumpToBottom: MutableState<Boolean>,
    ) {
        snapshotFlow {
            renderedMessages.isNotEmpty() &&
                !isFollowingState() &&
                !listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX)
        }.distinctUntilChanged()
            .collect { show -> showJumpToBottom.value = show }
    }
}
// endregion
