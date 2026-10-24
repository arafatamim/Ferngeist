package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.RequestPermissionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        // A session with no pending permission is a no-op, not an error.
        assertEquals(emptyList<String>(), flow.cancelPendingForSession("session-c"))
    }

    @Test
    fun `PermissionFlow cancelForSession cancels only matching session pendings`() {
        val flow = PermissionFlow()
        val deferred1 = CompletableDeferred<RequestPermissionOutcome>()
        val deferred2 = CompletableDeferred<RequestPermissionOutcome>()

        flow.addPending("tool-1", "session-1", deferred1)
        flow.addPending("tool-2", "session-2", deferred2)

        flow.cancelForSession("session-1")

        assertTrue(deferred1.isCancelled)
        assertNull(flow.takePending("tool-1"))
        assertNotNull(flow.takePending("tool-2"))
    }

    @Test
    fun `PermissionFlow cancelAll cleans up all pending requests`() {
        val flow = PermissionFlow()
        val deferred = CompletableDeferred<RequestPermissionOutcome>()
        flow.addPending("tool-a", "session-x", deferred)

        flow.cancelAll()

        assertTrue(deferred.isCancelled)
        assertNull(flow.takePending("tool-a"))
    }
}
