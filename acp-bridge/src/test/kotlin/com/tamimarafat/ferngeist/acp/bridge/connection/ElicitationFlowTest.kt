@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.CreateElicitationResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class ElicitationFlowTest {
    @Test
    fun cancelPendingForSession_completesMatchingDeferredsWithCancelAndReturnsKeys() {
        val flow = ElicitationFlow()
        val first = CompletableDeferred<CreateElicitationResponse>()
        val second = CompletableDeferred<CreateElicitationResponse>()
        flow.addPending(key = "key-1", sessionId = "session-a", deferred = first)
        flow.addPending(key = "key-2", sessionId = "session-a", deferred = second)

        val cancelled = flow.cancelPendingForSession("session-a")

        assertEquals(listOf("key-1", "key-2").sorted(), cancelled.sorted())
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals(ElicitationMappers.cancelResponse().action, first.getCompleted().action)
        assertEquals(ElicitationMappers.cancelResponse().action, second.getCompleted().action)
    }

    @Test
    fun cancelPendingForSession_leavesOtherSessionsEntriesTakeable() {
        val flow = ElicitationFlow()
        val mine = CompletableDeferred<CreateElicitationResponse>()
        val theirs = CompletableDeferred<CreateElicitationResponse>()
        flow.addPending(key = "key-1", sessionId = "session-a", deferred = mine)
        flow.addPending(key = "key-2", sessionId = "session-b", deferred = theirs)

        val cancelled = flow.cancelPendingForSession("session-a")

        assertEquals(listOf("key-1"), cancelled)
        assertNull(flow.takePending("key-1"))
        val untouched = flow.takePending("key-2")
        assertSame(theirs, untouched?.deferred)
        assertFalse(theirs.isCompleted)
    }

    @Test
    fun cancelPendingForSession_whenNoEntryMatches_returnsEmptyList() {
        val flow = ElicitationFlow()
        val other = CompletableDeferred<CreateElicitationResponse>()
        flow.addPending(key = "key-1", sessionId = "session-b", deferred = other)

        val cancelled = flow.cancelPendingForSession("session-a")

        assertTrue(cancelled.isEmpty())
        assertSame(other, flow.takePending("key-1")?.deferred)
    }
}
