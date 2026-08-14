package com.tamimarafat.ferngeist.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollPolicyTest {
    private fun policyWithClock(startMs: Long = 500L): Pair<ChatScrollPolicy, () -> Long> {
        var now = startMs
        val clock = { now }
        val policy = ChatScrollPolicy(clock)
        return policy to {
            now += 1
            now
        }
    }

    @Test
    fun `initial state is Following`() {
        val (policy) = policyWithClock()
        assertTrue(policy.isFollowing)
    }

    @Test
    fun `first streaming bubble resize returns DelayedFollow`() {
        val (policy, tick) = policyWithClock()
        tick()

        val decision = policy.onStreamingBubbleResized()
        assertTrue(decision is ScrollDecision.DelayedFollow)
        assertEquals(AutoScrollConfig.INITIAL_FOLLOW_SETTLE_MS, (decision as ScrollDecision.DelayedFollow).delayMs)
    }

    @Test
    fun `subsequent streaming bubble resize returns SnapToBottom`() {
        val (policy, tick) = policyWithClock()
        tick()

        policy.onStreamingBubbleResized() // first call → DelayedFollow
        val decision = policy.onStreamingBubbleResized() // second call
        assertTrue(decision is ScrollDecision.SnapToBottom)
    }

    @Test
    fun `streaming bubble resize returns None when paused`() {
        val (policy, tick) = policyWithClock()
        tick()

        policy.onUserScrolled()
        val decision = policy.onStreamingBubbleResized()
        assertTrue(decision is ScrollDecision.None)
    }

    @Test
    fun `user scroll pauses following`() {
        val (policy, tick) = policyWithClock()
        tick()
        assertTrue(policy.isFollowing)

        policy.onUserScrolled()
        assertFalse(policy.isFollowing)
    }

    @Test
    fun `user scroll returns CancelPending`() {
        val (policy, tick) = policyWithClock()
        tick()

        val decision = policy.onUserScrolled()
        assertTrue(decision is ScrollDecision.CancelPending)
    }

    @Test
    fun `user scroll while already paused stays paused and returns CancelPending`() {
        val (policy, tick) = policyWithClock()
        tick()

        policy.onUserScrolled()
        assertFalse(policy.isFollowing)

        val decision = policy.onUserScrolled()
        assertFalse(policy.isFollowing)
        assertTrue(decision is ScrollDecision.CancelPending)
    }

    @Test
    fun `insets returns None when paused`() {
        val (policy, tick) = policyWithClock()
        tick()

        policy.onUserScrolled()
        val decision = policy.onInsetsChanged(messageCount = 5)
        assertTrue(decision is ScrollDecision.None)
    }

    @Test
    fun `insets returns DelayedFollow when following`() {
        val (policy, tick) = policyWithClock()
        tick()

        val decision = policy.onInsetsChanged(messageCount = 5)
        assertTrue(decision is ScrollDecision.DelayedFollow)
        assertEquals(AutoScrollConfig.COMPOSER_FOLLOW_SETTLE_MS, (decision as ScrollDecision.DelayedFollow).delayMs)
    }

    @Test
    fun `insets emits delayed follow for repeated layout changes`() {
        val (policy, tick) = policyWithClock()
        tick()

        val first = policy.onInsetsChanged(messageCount = 5)
        assertTrue(first is ScrollDecision.DelayedFollow)

        val second = policy.onInsetsChanged(messageCount = 5)
        assertTrue(second is ScrollDecision.DelayedFollow)
    }

    @Test
    fun `insets delayed follow always uses composer settle delay`() {
        var now = 500L
        val policy = ChatScrollPolicy(clock = { now })

        val first = policy.onInsetsChanged(messageCount = 5)
        assertTrue(first is ScrollDecision.DelayedFollow)
        assertEquals(
            AutoScrollConfig.COMPOSER_FOLLOW_SETTLE_MS,
            (first as ScrollDecision.DelayedFollow).delayMs,
        )

        val second = policy.onInsetsChanged(messageCount = 5)
        assertTrue(second is ScrollDecision.DelayedFollow)
        assertEquals(
            AutoScrollConfig.COMPOSER_FOLLOW_SETTLE_MS,
            (second as ScrollDecision.DelayedFollow).delayMs,
        )
    }

    @Test
    fun `insets returns None when messageCount is zero`() {
        val (policy, tick) = policyWithClock()
        tick()

        val decision = policy.onInsetsChanged(messageCount = 0)
        assertTrue(decision is ScrollDecision.None)
    }

    @Test
    fun `markRestored suppresses next insets follow`() {
        val (policy, tick) = policyWithClock()
        tick()

        val restoreDecision = policy.markRestored(isFollowing = true)
        assertTrue(restoreDecision is ScrollDecision.DelayedFollow)
        assertEquals(
            AutoScrollConfig.INITIAL_FOLLOW_SETTLE_MS,
            (restoreDecision as ScrollDecision.DelayedFollow).delayMs,
        )

        val first = policy.onInsetsChanged(messageCount = 5)
        assertTrue(first is ScrollDecision.None)

        // Second call after suppression should fire normally
        val second = policy.onInsetsChanged(messageCount = 5)
        assertTrue(second is ScrollDecision.DelayedFollow)
    }

    @Test
    fun `markRestored false sets PausedByUser`() {
        val (policy, tick) = policyWithClock()
        tick()

        val decision = policy.markRestored(isFollowing = false)
        assertTrue(decision is ScrollDecision.None)
        assertFalse(policy.isFollowing)
    }

    @Test
    fun `markRestored true keeps Following`() {
        val (policy, tick) = policyWithClock()
        tick()
        assertTrue(policy.isFollowing)

        val decision = policy.markRestored(isFollowing = true)
        assertTrue(decision is ScrollDecision.DelayedFollow)
        assertTrue(policy.isFollowing)
    }

    @Test
    fun `markRestored false resumes following via idle timeout`() {
        var now = 0L
        val policy = ChatScrollPolicy(clock = { now })

        policy.markRestored(isFollowing = false)
        assertFalse(policy.isFollowing)

        now += AutoScrollConfig.USER_RESUME_IDLE_MS + 1
        val decision = policy.onIdleTimeout(isAtBottom = true)
        assertTrue(decision is ScrollDecision.SnapToBottom)
        assertTrue(policy.isFollowing)
    }

    @Test
    fun `idle timeout resumes when paused and idle and at bottom`() {
        var now = 0L
        val policy = ChatScrollPolicy(clock = { now })

        policy.onUserScrolled()
        now += AutoScrollConfig.USER_RESUME_IDLE_MS + 1

        val decision = policy.onIdleTimeout(isAtBottom = true)
        assertTrue(decision is ScrollDecision.SnapToBottom)
        assertTrue(policy.isFollowing)
    }

    @Test
    fun `idle timeout blocked by recent user scroll`() {
        var now = 0L
        val policy = ChatScrollPolicy(clock = { now })

        policy.onUserScrolled()
        now += 10 // not enough time

        val decision = policy.onIdleTimeout(isAtBottom = true)
        assertTrue(decision is ScrollDecision.None)
    }

    @Test
    fun `idle timeout requires at bottom`() {
        var now = 0L
        val policy = ChatScrollPolicy(clock = { now })

        policy.onUserScrolled()
        now += AutoScrollConfig.USER_RESUME_IDLE_MS + 1

        val decision = policy.onIdleTimeout(isAtBottom = false)
        assertTrue(decision is ScrollDecision.None)
    }

    @Test
    fun `idle timeout returns None when already following`() {
        val (policy, tick) = policyWithClock()
        tick()

        val decision = policy.onIdleTimeout(isAtBottom = true)
        assertTrue(decision is ScrollDecision.None)
    }

    @Test
    fun `manual bottom resume during streaming returns SnapToBottom`() {
        val (policy, tick) = policyWithClock()
        tick()

        policy.onUserScrolled()
        assertFalse(policy.isFollowing)

        val decision = policy.checkManualBottomResume(isAtBottom = true, isStreaming = true)
        assertTrue(decision is ScrollDecision.SnapToBottom)
        assertTrue(policy.isFollowing)
    }

    @Test
    fun `manual bottom resume blocked when not streaming`() {
        val (policy, tick) = policyWithClock()
        tick()

        policy.onUserScrolled()
        val decision = policy.checkManualBottomResume(isAtBottom = true, isStreaming = false)
        assertTrue(decision is ScrollDecision.None)
        assertFalse(policy.isFollowing)
    }

    @Test
    fun `send always transitions to Following and returns SendFollow`() {
        val (policy, tick) = policyWithClock()
        tick()

        policy.onUserScrolled()
        assertFalse(policy.isFollowing)

        val decision = policy.requestScrollToBottomForSend()
        assertTrue(decision is ScrollDecision.SendFollow)
        assertTrue(policy.isFollowing)
    }

    @Test
    fun `resumeFollowing transitions to Following without scroll`() {
        val (policy, tick) = policyWithClock()
        tick()

        policy.onUserScrolled()
        assertFalse(policy.isFollowing)

        val decision = policy.resumeFollowing()
        assertTrue(decision is ScrollDecision.None)
        assertTrue(policy.isFollowing)
    }
}
