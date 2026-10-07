package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionElicitationTest {
    private fun form(key: String) =
        ChatElicitationRequest.Form(
            key = key,
            message = "Which strategy?",
            sessionId = "ses-1",
        )

    @Test
    fun elicitationRequested_addsToPendingList() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses-1")

            runtime.onEvent(AppSessionEvent.ElicitationRequested(form("key-1")))

            val pending = runtime.snapshot.value.pendingElicitations
            assertEquals(listOf("key-1"), pending.map { it.key })
        }

    @Test
    fun elicitationResolved_removesFromPendingList() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses-1")
            runtime.onEvent(AppSessionEvent.ElicitationRequested(form("key-1")))
            runtime.onEvent(AppSessionEvent.ElicitationRequested(form("key-2")))

            runtime.onEvent(AppSessionEvent.ElicitationResolved("key-1"))

            val pending = runtime.snapshot.value.pendingElicitations
            assertEquals(listOf("key-2"), pending.map { it.key })
        }

    @Test
    fun elicitationRequested_twiceWithSameKey_replacesInsteadOfDuplicating() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses-1")
            runtime.onEvent(AppSessionEvent.ElicitationRequested(form("key-1")))
            runtime.onEvent(AppSessionEvent.ElicitationRequested(form("key-1")))

            assertEquals(1, runtime.snapshot.value.pendingElicitations.size)
        }

    @Test
    fun elicitationCompleted_leavesPendingListAlone() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses-1")
            runtime.onEvent(AppSessionEvent.ElicitationRequested(form("key-1")))

            // The request already resolved when the user consented; the completion
            // notification only reports the out-of-band interaction finished.
            runtime.onEvent(AppSessionEvent.ElicitationResolved("key-1"))
            runtime.onEvent(AppSessionEvent.ElicitationCompleted("github-oauth-001"))

            val pending =
                runtime.snapshot.value
                    .pendingElicitations
            assertTrue(pending.isEmpty())
        }
}
