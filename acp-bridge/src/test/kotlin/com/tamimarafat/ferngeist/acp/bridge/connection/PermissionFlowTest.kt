package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.RequestPermissionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class PermissionFlowTest {
    @Test
    fun cancelPendingForSession_completesMatchingDeferredsAndReturnsTheirToolCallIds() {
        val flow = PermissionFlow()
        val first = CompletableDeferred<RequestPermissionOutcome>()
        val second = CompletableDeferred<RequestPermissionOutcome>()
        flow.addPending(toolCallId = "call-1", sessionId = "session-a", deferred = first)
        flow.addPending(toolCallId = "call-2", sessionId = "session-a", deferred = second)

        val cancelled = flow.cancelPendingForSession("session-a")

        assertEquals(listOf("call-1", "call-2").sorted(), cancelled.sorted())
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertSame(RequestPermissionOutcome.Cancelled, first.getCompleted())
        assertSame(RequestPermissionOutcome.Cancelled, second.getCompleted())
    }

    @Test
    fun cancelPendingForSession_leavesOtherSessionsEntriesTakeable() {
        val flow = PermissionFlow()
        val mine = CompletableDeferred<RequestPermissionOutcome>()
        val theirs = CompletableDeferred<RequestPermissionOutcome>()
        flow.addPending(toolCallId = "call-1", sessionId = "session-a", deferred = mine)
        flow.addPending(toolCallId = "call-2", sessionId = "session-b", deferred = theirs)

        val cancelled = flow.cancelPendingForSession("session-a")

        assertEquals(listOf("call-1"), cancelled)
        assertNull(flow.takePending("call-1"))
        val untouched = flow.takePending("call-2")
        assertSame(theirs, untouched?.deferred)
        assertFalse(theirs.isCompleted)
    }

    @Test
    fun cancelPendingForSession_whenNoEntryMatches_returnsEmptyList() {
        val flow = PermissionFlow()
        val other = CompletableDeferred<RequestPermissionOutcome>()
        flow.addPending(toolCallId = "call-1", sessionId = "session-b", deferred = other)

        val cancelled = flow.cancelPendingForSession("session-a")

        assertTrue(cancelled.isEmpty())
        assertSame(other, flow.takePending("call-1")?.deferred)
    }

    @Test
    fun cancelPendingForSession_removesCancelledEntriesSoTakePendingReturnsNull() {
        val flow = PermissionFlow()
        val deferred = CompletableDeferred<RequestPermissionOutcome>()
        flow.addPending(toolCallId = "call-1", sessionId = "session-a", deferred = deferred)

        val cancelled = flow.cancelPendingForSession("session-a")

        assertEquals(listOf("call-1"), cancelled)
        assertNull(flow.takePending("call-1"))
        assertTrue(deferred.isCompleted)
        assertSame(RequestPermissionOutcome.Cancelled, deferred.getCompleted())
    }
}
