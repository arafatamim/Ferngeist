package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.Easing
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.FollowEnded
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.GlideArrived
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.JumpedToBottom
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.ReachedEnd
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.Released
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.Rested
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.RestorePending
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.Restored
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ScrollEvent.Sent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollPolicyTest {
    private val up = Released(towardEnd = false)
    private val down = Released(towardEnd = true)

    private fun policy(vararg events: ScrollEvent) = events.fold(ScrollPolicy()) { policy, event -> policy.on(event) }

    @Test
    fun `a send glides and follows, forgetting the reader's scroll and the pin`() {
        val policy = policy(up, JumpedToBottom(live = true), Sent)
        assertTrue(policy.inCharge && policy.gliding)
        assertFalse(policy.followsEnd(live = true))
    }

    @Test
    fun `a reader's scroll ends the glide, the follow and the pin`() {
        val policy = policy(Sent, JumpedToBottom(live = true), up)
        assertFalse(policy.inCharge || policy.gliding || policy.followsEnd(live = true) || policy.followsRail(1))
    }

    @Test
    fun `a live rail is followed only while one is open`() {
        assertTrue(policy(Sent).followsRail(1))
        assertFalse(policy(Sent).followsRail(0))
    }

    @Test
    fun `jump to bottom mid-turn pins the end until the turn ends`() {
        val pinned = policy(JumpedToBottom(live = true))
        assertTrue(pinned.followsEnd(live = true))
        assertFalse(pinned.on(FollowEnded(live = false)).followsEnd(live = true))
        assertFalse(policy(JumpedToBottom(live = false)).followsEnd(live = true))
    }

    @Test
    fun `resting near the end mid-turn does not engage auto-follow`() {
        assertFalse(policy(down, Rested(atEnd = true, live = true)).followsEnd(live = true))
    }

    @Test
    fun `a scroll that touches the absolute end mid-turn engages it`() {
        assertTrue(policy(down, ReachedEnd, Rested(atEnd = true, live = true)).followsEnd(live = true))
    }

    @Test
    fun `touching the end still engages follow when content grows away before rest`() {
        val policy = policy(down, ReachedEnd, Rested(atEnd = false, live = true))
        assertTrue(policy.followsEnd(live = true))
        assertTrue(policy.inCharge)
    }

    @Test
    fun `scrolling back up after touching the end does not engage it`() {
        assertFalse(policy(down, ReachedEnd, up, Rested(atEnd = true, live = true)).followsEnd(live = true))
        assertFalse(policy(down, ReachedEnd, up, Rested(atEnd = false, live = true)).followsEnd(live = true))
    }

    @Test
    fun `a sent prompt resting at the end is not pinned`() {
        val policy = policy(Sent, GlideArrived, ReachedEnd, Rested(atEnd = true, live = true))
        assertFalse(policy.followsEnd(live = true))
    }

    @Test
    fun `content growing away from the end stops following`() {
        assertFalse(policy(Rested(atEnd = false, live = true)).inCharge)
    }

    @Test
    fun `a send during a jump ease glides once the ease ends`() {
        val waiting = policy(Easing(active = true), Sent)
        assertFalse(waiting.inCharge)
        assertTrue(waiting.on(Easing(active = false)).let { it.inCharge && it.gliding })
    }

    @Test
    fun `an ease owns the list and hides the jump button`() {
        val policy = policy(JumpedToBottom(live = true), Easing(active = true))
        assertFalse(policy.followsRail(1) || policy.followsEnd(live = true) || policy.allowsJumpButton)
        assertTrue(policy.owned(live = false, activeRails = 0))
    }

    @Test
    fun `a restore cancelled by a drag stops owning the list`() {
        val policy = policy(RestorePending(pending = true), up, RestorePending(pending = false))
        assertFalse(policy.owned(live = false, activeRails = 0))
    }

    @Test
    fun `a new message is followed at rest and on opening, never mid-stream or mid-glide`() {
        assertTrue(policy().followsAppend(opening = false, live = false))
        assertTrue(policy().followsAppend(opening = true, live = true))
        assertFalse(policy().followsAppend(opening = false, live = true))
        assertFalse(policy(Sent).followsAppend(opening = true, live = false))
        assertFalse(policy(up).followsAppend(opening = false, live = false))
    }

    @Test
    fun `a restore applies the saved follow state`() {
        val policy = policy(RestorePending(pending = true), Restored(isFollowing = false))
        assertFalse(policy.inCharge || policy.restorePending)
    }
}
