package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatScrollSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

private const val USER_SCROLL_DELTA_THRESHOLD = 0.5f

/** Duration of the shortest jump-button scroll; longer ones scale up to [EASE_MAX_MS]. */
internal const val EASE_MIN_MS = 650

/** Duration of a jump that travels the full [EASE_MAX_VIEWPORTS]. */
private const val EASE_MAX_MS = 1000

/**
 * Farthest a jump eases, in viewports. A farther target is first placed this far away in one
 * frame, so the ease never has to compose the whole transcript.
 */
private const val EASE_MAX_VIEWPORTS = 2.5f

/** Duration of a short settle onto the end: a follow resuming, or a jump chasing a stream. */
private const val SETTLE_MS = 220

/** Most chase eases a jump to the bottom runs after its main ease; following takes over after. */
private const val CHASE_PASSES = 3

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
    val runner =
        remember(sessionId, scope, listState, policy, isFollowingState) {
            ChatScrollDecisionRunner(listState, scope, policy, isFollowingState)
        }
    val userScrollDetector = remember(runner) { runner.createUserScrollConnection() }
    // The keyboard and an expanding composer grow the bottom inset a little every frame, and a sent
    // or arriving message extends the list below the viewport. Pinning in that same frame —
    // SideEffect runs before the list measures — keeps the end in view as it happens. The
    // settle-delayed follows only fire once things stop changing, and on send the collapsing
    // composer restarts (and cancels) them every frame, so on their own the transcript sat still
    // and then snapped. A shrinking inset needs no help: the list clamps to its end.
    val bottomInsetPx = composerContentHeightPx + imeBottomPx
    val messageCount = renderedMessages.size
    val lastBottomInsetPx = remember(sessionId) { mutableIntStateOf(bottomInsetPx) }
    val lastMessageCount = remember(sessionId) { mutableIntStateOf(messageCount) }
    SideEffect {
        val grew = bottomInsetPx > lastBottomInsetPx.intValue || messageCount > lastMessageCount.intValue
        lastBottomInsetPx.intValue = bottomInsetPx
        lastMessageCount.intValue = messageCount
        if (grew && !restorePending) runner.followGrowth()
    }
    // The viewport is measured in pixels; snapshots record it in dp so the value stays
    // comparable across a fold or rotate (density can change with the display).
    val density = LocalDensity.current
    val viewportWidthDp = {
        val viewportPx = listState.layoutInfo.viewportSize.width
        with(density) { viewportPx.toDp().value.toInt() }
    }
    val observer =
        remember(sessionId, listState, policy, runner, onScrollSnapshotChanged, density) {
            ChatScrollSnapshotObserver(
                listState = listState,
                policy = policy,
                runner = runner,
                isFollowingState = { isFollowingState.value },
                onScrollSnapshotChanged = onScrollSnapshotChanged,
                viewportWidthDp = viewportWidthDp,
            )
        }
    LaunchedEffect(policy, listState) { observer.observeIdleTimeout() }
    LaunchedEffect(policy, activelyStreaming) { observer.observeManualBottomResume(activelyStreaming) }
    // Not keyed on the following state: every resume already settles onto the end, and a second
    // settle queued by the state flip made a resume move twice.
    LaunchedEffect(
        composerContentHeightPx,
        imeBottomPx,
        renderedMessages.size,
        restorePending,
    ) {
        observer.onInsetsChanged(renderedMessages.size, restorePending)
    }
    LaunchedEffect(listState, renderedMessages, isFollowingState.value, sessionId) {
        observer.observePersistence(renderedMessages)
    }
    LaunchedEffect(
        restorePending,
        restoreReady,
        renderedMessages,
        restoredScrollSnapshot?.savedAt,
        composerContentHeightPx,
        imeBottomPx,
    ) {
        observer.restoreSnapshot(restoredScrollSnapshot, renderedMessages, restorePending, restoreReady) {
            restorePending =
                false
        }
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
    val containerWidthDp: Int,
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

/**
 * Eases to the top of item 0 along an exact, measured distance. See [glideOnto].
 */
internal suspend fun LazyListState.easeScrollToTop() {
    val anchor = layoutInfo.visibleItemsInfo.firstOrNull() ?: return
    val estimate = -distanceToTop()
    scrollToItem(0)
    // Where the item that was at the top now sits gives the exact distance travelled; only when
    // it has scrolled out of view is the distance an estimate, and then the start jumps anyway.
    val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index }
    val distance = landed?.let { (it.offset - anchor.offset).toFloat() } ?: min(estimate, maxEasePx())
    glideOnto(-distance, exact = landed != null)
}

/**
 * Eases to the end of the content along an exact, measured distance. See [glideOnto].
 */
internal suspend fun LazyListState.easeScrollToBottom() {
    val anchor = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val estimate = distanceToBottom()
    scrollToItem(layoutInfo.totalItemsCount - 1)
    val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index }
    val distance =
        landed?.let { (anchor.offset + anchor.size - (it.offset + it.size)).toFloat() } ?: min(estimate, maxEasePx())
    glideOnto(distance, exact = landed != null)
    // A reply still streaming grows during the ease, past the distance it set out to cover. Chase
    // the new end in short eases; snapping onto it hopped by every line that streamed meanwhile.
    repeat(CHASE_PASSES) {
        val left = distanceToBottom()
        if (left < 1f) return
        animateScrollBy(left, tween(SETTLE_MS, easing = EaseOutCubic))
    }
}

/**
 * Brings the end of the content into view: eases there when it is on screen, snaps otherwise.
 * For the follow resumes, where the list is already near its end; a snap there read as a jolt.
 */
internal suspend fun LazyListState.settleToBottom() {
    withFrameNanos { }
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    if (last == null || last.index != info.totalItemsCount - 1) return scrollToBottom()
    val distance = (last.offset + last.size - info.viewportEndOffset).toFloat()
    if (distance >= 1f) animateScrollBy(distance, tween(SETTLE_MS, easing = EaseOutCubic))
}

/**
 * Called with the list already resting on its target: steps back [travel] pixels and eases
 * forward onto the target again.
 *
 * The step back happens in the same frame as the snap to the target, so when the distance was
 * measured [exact]ly the list visibly never moves before the ease starts — it simply glides from
 * where it was. Easing a known distance is what makes the curve a real curve: an ease that
 * re-estimates an off-screen target every frame keeps stretching as taller items come into view
 * and reads as a linear scroll with a hard stop.
 *
 * A far target cannot be measured without composing everything in between, so the start jumps to
 * [EASE_MAX_VIEWPORTS] away and eases out only — the jump reads as the fast start of the motion.
 */
private suspend fun LazyListState.glideOnto(
    travel: Float,
    exact: Boolean,
) {
    if (travel == 0f) return
    val stepped = -scrollBy(-travel)
    val fraction = (abs(stepped) / maxEasePx()).coerceAtMost(1f)
    animateScrollBy(
        value = stepped,
        animationSpec =
            tween(
                durationMillis = EASE_MIN_MS + ((EASE_MAX_MS - EASE_MIN_MS) * fraction).toInt(),
                easing = if (exact) EaseInOutCubic else EaseOutCubic,
            ),
    )
}

private fun LazyListState.maxEasePx(): Float =
    layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset } * EASE_MAX_VIEWPORTS

/** Signed pixels from the current position to the top of item 0; negative scrolls up. */
private fun LazyListState.distanceToTop(): Float {
    val info = layoutInfo
    info.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { return it.offset.toFloat() }
    return -(firstVisibleItemIndex * info.averageItemSpan() + firstVisibleItemScrollOffset)
}

/** Signed pixels from the current position to the end of the content; positive scrolls down. */
private fun LazyListState.distanceToBottom(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    val itemsBelow = info.totalItemsCount - 1 - last.index
    return itemsBelow * info.averageItemSpan() + (last.offset + last.size - info.viewportEndOffset)
}

private fun LazyListLayoutInfo.averageItemSpan(): Float {
    val items = visibleItemsInfo
    if (items.isEmpty()) return 0f
    return items.sumOf { it.size }.toFloat() / items.size + mainAxisItemSpacing
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

    /** True while a jump button's ease is running. See [ease]. */
    private var easing = false

    /**
     * Applies a [ScrollDecision] from the policy:
     * - [ScrollDecision.None]: syncs following state, no scroll.
     * - [ScrollDecision.CancelPending]: cancels any in-flight job.
     * - Otherwise: cancels existing job, launches a new scroll coroutine.
     */
    fun run(decision: ScrollDecision) {
        isFollowingState.value = policy.isFollowing
        if (decision is ScrollDecision.None || easing) return
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
                is ScrollDecision.SnapToBottom -> listState.settleToBottom()
                is ScrollDecision.DelayedFollow -> {
                    delay(decision.delayMs)
                    listState.settleToBottom()
                }
                is ScrollDecision.SendFollow -> {
                    repeat(AutoScrollConfig.SEND_FOLLOW_PASSES) {
                        listState.scrollToBottom()
                        delay(AutoScrollConfig.SEND_FOLLOW_DELAY_MS)
                    }
                }
                is ScrollDecision.None, is ScrollDecision.CancelPending, is ScrollDecision.FollowGrowth -> Unit
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

    /**
     * Keeps the list on its end while the streaming bubble, the bottom inset or the message list
     * grows. Not routed through [run]: it must land in this frame's measure, so it cannot wait for
     * a coroutine.
     *
     * A position request, not a scroll delta: resting at the end, the list reports it cannot scroll
     * forward and drops a forward delta before the bigger spacer is even measured. The index comes
     * from the previous layout, so after an append it names the first new item (or still the
     * spacer, once the window is full); either way the measure clamps the content end onto the
     * viewport end. ponytail: a new message taller than the viewport would show its top, not its
     * end; pass the new item count through if that ever happens.
     */
    fun followGrowth() {
        if (easing || !policy.shouldFollowGrowth()) return
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) listState.requestScrollToItem(lastIndex)
    }

    /** Streaming resize: delegate to policy and apply the decision. */
    fun onStreamLayoutSettled() {
        val decision = policy.onStreamingBubbleResized()
        if (decision is ScrollDecision.FollowGrowth) followGrowth() else run(decision)
    }

    /** Send button: delegate to policy and apply the decision. */
    fun onSendMessage() {
        run(policy.requestScrollToBottomForSend())
    }

    /** Scroll to top: route through policy as a user scroll, then ease there. */
    suspend fun jumpToTop() {
        // Tapping "scroll to top" is a deliberate move away from the bottom.
        // Route it through the same transition as a manual scroll so the policy
        // leaves Following and cancels any pending follow job. Otherwise the
        // Following-state mechanisms (streaming-bubble resize, insets follow)
        // immediately scroll back to the bottom and fight this jump.
        run(policy.onUserScrolled())
        ease { listState.easeScrollToTop() }
    }

    /** Scroll to bottom: resume following without the multi-pass send follow, then ease there. */
    suspend fun jumpToBottom() {
        // Resume following without firing the multi-pass send follow.
        // The user-initiated jump should be a single smooth animation, not
        // the 3-pass scroll-to-bottom that sending a message uses.
        run(policy.resumeFollowing())
        ease { listState.easeScrollToBottom() }
    }

    /**
     * Runs a jump animation with every other scroll decision held off. Any of them (a streaming
     * follow, the bottom-arrival snap as the jump nears the end) takes the list's scroll mutex
     * and would cut the ease short. Policy state still updates; only the scroll is skipped, and
     * the jump re-measures its target every frame, so it lands where the follow would have.
     */
    private suspend fun ease(animation: suspend () -> Unit) {
        scrollJob?.cancel()
        easing = true
        programmaticScrolling = true
        try {
            animation()
        } finally {
            easing = false
            programmaticScrolling = false
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
 *
 * @param viewportWidthDp current list viewport width in dp, read at save/restore time
 */
private class ChatScrollSnapshotObserver(
    private val listState: LazyListState,
    private val policy: ChatScrollPolicy,
    private val runner: ChatScrollDecisionRunner,
    private val isFollowingState: () -> Boolean,
    private val onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
    private val viewportWidthDp: () -> Int,
) {
    /**
     * 1. Idle timeout. Event-driven: arms when the list comes to rest at the
     * bottom with following paused, waits out the policy's quiet window, then
     * re-checks once against fresh state.
     *
     * "At rest" includes no scroll in progress. Without it this armed the moment
     * the list reached the bottom, usually with the finger still down dragging
     * into the end, so the check saw a fresh user scroll and rejected; nothing
     * re-armed it, and following stayed paused after any hand scroll to the
     * bottom. Now lifting the finger is the event, and scrolling again cancels
     * the pending check (collectLatest) and re-arms on the next rest.
     *
     * Every input is snapshot state, and all of it is read on every pass. The
     * flow re-runs only when state it read last time changes: reading the plain
     * `policy.isFollowing` first let `&&` short-circuit while following, leaving
     * no dependencies at all, so the flow never ran again and this resume never
     * fired. [isFollowingState] is the observable mirror of the policy.
     */
    suspend fun observeIdleTimeout() {
        snapshotFlow {
            val atRest =
                !listState.isScrollInProgress &&
                    listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX)
            val paused = !isFollowingState()
            atRest && paused
        }.distinctUntilChanged()
            .collectLatest { restingAtBottom ->
                if (!restingAtBottom) return@collectLatest
                delay(AutoScrollConfig.USER_RESUME_IDLE_MS)
                runner.run(
                    policy.onIdleTimeout(
                        listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX),
                    ),
                )
            }
    }

    /**
     * 2. Manual bottom resume — fires when the user brings the list to rest at the bottom
     * mid-stream. At rest, not on arrival: resuming with the finger still down let the stream's
     * follow pull the list to its end under the drag.
     */
    suspend fun observeManualBottomResume(activelyStreaming: Boolean) {
        snapshotFlow {
            !listState.isScrollInProgress && listState.isAtBottom(AutoScrollConfig.RESUME_TOLERANCE_PX)
        }.distinctUntilChanged()
            .collect { restingAtBottom ->
                runner.run(policy.checkManualBottomResume(restingAtBottom, activelyStreaming))
            }
    }

    /** 3. Composer/IME insets change — deferred to insets follow when not restoring. */
    fun onInsetsChanged(
        messageCount: Int,
        restorePending: Boolean,
    ) {
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
                    containerWidthDp = viewportWidthDp(),
                    isFollowing = isFollowingState(),
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
                        containerWidthDp = observation.containerWidthDp,
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
        val currentSnapshot =
            snapshot ?: run {
                onRestoreDone()
                return
            }
        val restoredIndex =
            currentSnapshot.anchorMessageId
                ?.let { anchorId -> renderedMessages.indexOfFirst { it.id == anchorId } }
                ?.takeIf { it >= 0 }
                ?: currentSnapshot.firstVisibleItemIndex.coerceIn(0, renderedMessages.lastIndex)
        // Two-pass: the first scrollToItem positions the index, but layout
        // may not have settled yet. The second pass corrects any offset drift.
        // The offset is re-anchored to the message top when the viewport width
        // changed (fold, rotate, multi-window), because item heights re-flowed.
        repeat(2) {
            withFrameNanos { }
            listState.scrollToItem(
                index = restoredIndex,
                scrollOffset = currentSnapshot.offsetForRestore(viewportWidthDp()).coerceAtLeast(0),
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
