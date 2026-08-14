package com.tamimarafat.ferngeist.feature.chat.ui

import android.os.SystemClock

// region: Auto-Scroll Configuration

/**
 * Configuration constants for the auto-scroll mechanism.
 *
 * ## Design Tradeoffs
 * - [INITIAL_FOLLOW_SETTLE_MS] is longer (420ms) to allow initial content to fully render
 *   before scrolling, preventing jarring jumps during first load.
 * - [COMPOSER_FOLLOW_SETTLE_MS] is a trailing settle delay for keyboard/composer
 *   layout changes. Each new inset change replaces the pending follow request.
 * - [USER_SCROLL_SIGNAL_WINDOW_MS] (220ms) filters out accidental touch noise.
 * - [USER_RESUME_IDLE_MS] (320ms) gives users time to read before auto-resuming.
 * - [FOLLOW_TOLERANCE_PX] (24px) allows "close enough" positioning to avoid micro-scrolls.
 * - [RESUME_TOLERANCE_PX] (48px) is more lenient for auto-resume to feel natural.
 * - [MAX_SCROLL_BY_PX] (720px) bounds a single scroll-by step to prevent overshoot.
 */
internal object AutoScrollConfig {
    const val INITIAL_FOLLOW_SETTLE_MS = 420L
    const val COMPOSER_FOLLOW_SETTLE_MS = 120L
    const val USER_SCROLL_SIGNAL_WINDOW_MS = 220L
    const val USER_RESUME_IDLE_MS = 320L
    const val MAX_SCROLL_BY_PX = 720
    const val FOLLOW_TOLERANCE_PX = 24
    const val RESUME_TOLERANCE_PX = 48
    const val SEND_FOLLOW_PASSES = 3
    const val SEND_FOLLOW_DELAY_MS = 32L
    const val SCROLL_DEBOUNCE_MS = 1000L
    const val SCROLL_CORRECTION_MAX_PASSES = 2
}
// endregion

// region: Auto-Scroll State

/**
 * Sealed class representing the auto-scroll state machine.
 *
 * ## State Transitions
 * ```
 *   [Initial] → [Following] ←──────────────────────┐
 *                    │  onUserScrolled()             │
 *                    ▼                               │
 *              [PausedByUser] ───────────────────────┘
 *                    │  onIdleTimeout(isAtBottom=true)
 *                    │  OR checkManualBottomResume(isAtBottom=true, isStreaming=true)
 *                    │  OR requestScrollToBottomForSend()
 *                    └───────────────────────────────┘
 * ```
 *
 * ## Responsibilities
 * - [Following]: Auto-scroll to bottom on content changes
 * - [PausedByUser]: Respect user's manual scroll position
 */
internal sealed class AutoScrollState {
    /** Auto-scroll is active; scroll to bottom on content changes */
    data object Following : AutoScrollState()

    /** User manually scrolled; auto-scroll paused until resume conditions are met */
    data object PausedByUser : AutoScrollState()
}
// endregion

// region: Scroll Decision

/**
 * Decisions returned by [ChatScrollPolicy], indicating what scroll action the caller
 * should perform. These replace the old [ScrollSideEffect] flow-based side effects.
 */
internal sealed class ScrollDecision {
    /** No scroll action needed. */
    data object None : ScrollDecision()

    /** Cancel the current pending scroll job without starting a new one. */
    data object CancelPending : ScrollDecision()

    /** Immediately snap to the last item in a single frame-synced pass. */
    data object SnapToBottom : ScrollDecision()

    /** Wait [delayMs] then scroll to bottom. */
    data class DelayedFollow(
        val delayMs: Long,
    ) : ScrollDecision()

    /** Multi-pass scroll for user-initiated send (3 passes, 32ms apart). */
    data object SendFollow : ScrollDecision()
}
// endregion

// region: ChatScrollPolicy

/**
 * Pure-Kotlin auto-scroll state machine. No Compose, Android, or LazyListState
 * dependencies.
 *
 * All time is obtained via [clock], making the class trivially testable with fake
 * clocks. "At bottom" checks are caller-provided booleans — the policy does not
 * inspect the list.
 *
 * ## Usage
 * ```
 * val policy = ChatScrollPolicy()
 * // User scrolled up
 * policy.onUserScrolled()               // → None, state becomes PausedByUser
 * // Keyboard opened while paused
 * policy.onInsetsChanged(messageCount=5) // → None (paused)
 * // User scrolled back to bottom while streaming
 * policy.checkManualBottomResume(true, true) // → SnapToBottom, state → Following
 * // User sends a message
 * policy.requestScrollToBottomForSend()  // → SendFollow, state → Following
 * ```
 */
internal class ChatScrollPolicy(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    private var _state: AutoScrollState = AutoScrollState.Following

    /** Current auto-scroll state snapshot. */
    val state: AutoScrollState get() = _state

    /** Snapshot of current following state. */
    val isFollowing: Boolean get() = _state is AutoScrollState.Following

    // -- internal timing fields ------------------------------------------------
    private var lastUserScrollTimeMs: Long = 0L
    private var hasHandledInitialFollow: Boolean = false
    private var skipNextInsetsFollow: Boolean = false

    // -- event handlers (each returns a ScrollDecision) -------------------------

    /**
     * The user performed a scroll gesture. If currently Following, pause.
     * Returns [ScrollDecision.CancelPending] to signal that any pending
     * scroll job should be cancelled.
     */
    fun onUserScrolled(): ScrollDecision {
        lastUserScrollTimeMs = clock()
        if (_state is AutoScrollState.Following) {
            _state = AutoScrollState.PausedByUser
        }
        // CancelPending — not None — so the caller kills any in-flight
        // DelayedFollow job. Without this, a 420ms pending follow would
        // yank the user back to bottom long after they scrolled away.
        return ScrollDecision.CancelPending
    }

    /**
     * Keyboard/composer insets changed.
     * Uses [AutoScrollConfig.COMPOSER_FOLLOW_SETTLE_MS] as a trailing settle delay.
     * Respects [skipNextInsetsFollow] (set by [markRestored]).
     *
     * @param messageCount number of messages currently rendered (0 = no scroll)
     */
    fun onInsetsChanged(messageCount: Int): ScrollDecision {
        // Suppress the first insets change after snapshot restoration so the
        // restored scroll position isn't yanked by a keyboard resize.
        if (skipNextInsetsFollow) {
            skipNextInsetsFollow = false
            return ScrollDecision.None
        }
        if (_state !is AutoScrollState.Following || messageCount == 0) {
            return ScrollDecision.None
        }
        return ScrollDecision.DelayedFollow(AutoScrollConfig.COMPOSER_FOLLOW_SETTLE_MS)
    }

    /**
     * A streaming bubble's content grew. On first call returns a longer delayed
     * follow; on subsequent calls returns an immediate snap. Only acts while
     * Following.
     */
    fun onStreamingBubbleResized(): ScrollDecision {
        if (_state !is AutoScrollState.Following) return ScrollDecision.None
        // First resize needs a longer settle (420ms) so the initial render
        // finishes before scrolling. Subsequent resizes during the same
        // stream snap immediately — the content is already on screen.
        if (!hasHandledInitialFollow) {
            hasHandledInitialFollow = true
            return ScrollDecision.DelayedFollow(AutoScrollConfig.INITIAL_FOLLOW_SETTLE_MS)
        }
        return ScrollDecision.SnapToBottom
    }

    /**
     * Periodic idle check. If PausedByUser, enough time has elapsed, the user
     * hasn't recently scrolled, and the list is at the bottom → resume following
     * and snap to bottom.
     *
     * @param isAtBottom caller-observed "is the list at bottom" within
     *   [AutoScrollConfig.RESUME_TOLERANCE_PX]
     */
    fun onIdleTimeout(isAtBottom: Boolean): ScrollDecision {
        val now = clock()
        val timeSinceLastScroll = now - lastUserScrollTimeMs
        // Signal window filters out scroll "noise" — rapid micro-movements
        // that happen immediately after a real user scroll.
        val recentlyUserScrolled =
            timeSinceLastScroll <= AutoScrollConfig.USER_SCROLL_SIGNAL_WINDOW_MS

        // All four conditions must hold: paused, no recent scroll noise,
        // enough idle time has passed, and the user is actually at bottom.
        if (
            _state is AutoScrollState.PausedByUser &&
            !recentlyUserScrolled &&
            timeSinceLastScroll >= AutoScrollConfig.USER_RESUME_IDLE_MS &&
            isAtBottom
        ) {
            _state = AutoScrollState.Following
            return ScrollDecision.SnapToBottom
        }
        return ScrollDecision.None
    }

    /**
     * Called when the list reaches the bottom while streaming. If paused,
     * resume following and snap to bottom.
     *
     * @param isAtBottom caller-observed "is the list at bottom"
     * @param isStreaming whether a streaming response is in progress
     */
    fun checkManualBottomResume(
        isAtBottom: Boolean,
        isStreaming: Boolean,
    ): ScrollDecision {
        // Separate from idle timeout: this fires immediately via
        // snapshotFlow when the user drags to bottom mid-stream.
        // Idle timeout is a periodic poll that waits for quiet time.
        if (isAtBottom && isStreaming && _state is AutoScrollState.PausedByUser) {
            _state = AutoScrollState.Following
            return ScrollDecision.SnapToBottom
        }
        return ScrollDecision.None
    }

    // -- imperative actions (may mutate state, may return decisions) -----------

    /**
     * Called after snapshot restoration finishes. Restores the following/paused
     * state from the snapshot and suppresses the next insets-triggered scroll
     * so the restored position isn't disturbed.
     */
    fun markRestored(isFollowing: Boolean): ScrollDecision {
        _state =
            if (isFollowing) {
                AutoScrollState.Following
            } else {
                AutoScrollState.PausedByUser
            }
        skipNextInsetsFollow = true
        return if (isFollowing) {
            ScrollDecision.DelayedFollow(AutoScrollConfig.INITIAL_FOLLOW_SETTLE_MS)
        } else {
            ScrollDecision.None
        }
    }

    /**
     * Transition to Following (no scroll action). Used when the user sends
     * a message to reset the state without an immediate scroll.
     */
    fun resumeFollowing(): ScrollDecision {
        _state = AutoScrollState.Following
        return ScrollDecision.None
    }

    /**
     * Transition to Following AND request a multi-pass send-follow scroll.
     * Called when the user sends a message.
     */
    fun requestScrollToBottomForSend(): ScrollDecision {
        _state = AutoScrollState.Following
        return ScrollDecision.SendFollow
    }
}
// endregion
