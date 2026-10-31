package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.TimeSource

class SessionTurnStatusTest {
    @Test
    fun `a prompt retains its start through attention and completion gets a new origin`() =
        runTest {
            val runtime = runtime()
            val beforeStart = TimeSource.Monotonic.markNow()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            val start = runtime.snapshot.value.turnStatusSince
            assertNotNull(start)
            assertTrue(start!! >= beforeStart)
            runtime.permissionRequested()
            runtime.onEvent(AppSessionEvent.ToolPermissionResolved("call-1"))
            assertEquals(start, runtime.snapshot.value.turnStatusSince)

            val beforeCompletion = TimeSource.Monotonic.markNow()
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))
            val finished = runtime.snapshot.value.turnStatusSince
            assertNotNull(finished)
            assertTrue(finished!! >= beforeCompletion)
            runtime.markReady()
            assertNull(runtime.snapshot.value.turnStatusSince)
        }

    private fun runtime() = SessionRuntime(sessionId = "ses_status")

    private suspend fun SessionRuntime.permissionRequested(toolCallId: String = "call-1") =
        onEvent(
            AppSessionEvent.ToolPermissionRequested(
                toolCallId = toolCallId,
                requestId = toolCallId,
                title = "Run command?",
                options = emptyList(),
            ),
        )

    private suspend fun SessionRuntime.elicitationRequested() =
        onEvent(
            AppSessionEvent.ElicitationRequested(
                ChatElicitationRequest.Form(
                    key = "elicit-1",
                    message = "Pick one",
                    sessionId = "ses_status",
                ),
            ),
        )

    private fun SessionRuntime.status(): TurnStatus = snapshot.value.turnStatus

    @Test
    fun `a new prompt is working`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())

            assertEquals(TurnStatus.WORKING, runtime.status())
        }

    @Test
    fun `a pending permission is attention and resolution returns to working`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())

            runtime.permissionRequested()
            assertEquals(TurnStatus.NEEDS_ATTENTION, runtime.status())

            runtime.onEvent(AppSessionEvent.ToolPermissionResolved("call-1"))
            assertEquals(TurnStatus.WORKING, runtime.status())
        }

    @Test
    fun `a pending elicitation is attention and resolution returns to working`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())

            runtime.elicitationRequested()
            assertEquals(TurnStatus.NEEDS_ATTENTION, runtime.status())

            runtime.onEvent(AppSessionEvent.ElicitationResolved("elicit-1"))
            assertEquals(TurnStatus.WORKING, runtime.status())
        }

    @Test
    fun `a completed url elicitation stops needing attention`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(
                AppSessionEvent.ElicitationRequested(
                    ChatElicitationRequest.Url(
                        key = "url-1",
                        message = "Open the link",
                        sessionId = "ses_status",
                        elicitationId = "el-1",
                        url = "https://example.test/auth",
                    ),
                ),
            )
            assertEquals(TurnStatus.NEEDS_ATTENTION, runtime.status())

            runtime.onEvent(AppSessionEvent.ElicitationCompleted("el-1"))
            assertEquals(TurnStatus.WORKING, runtime.status())
        }

    @Test
    fun `a successful turn is finished until the next prompt`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.AgentMessage("done"))
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))

            assertEquals(TurnStatus.FINISHED, runtime.status())
            runtime.onEvent(AppSessionEvent.SessionInfoUpdated(title = "later", updatedAt = null))
            assertEquals(TurnStatus.FINISHED, runtime.status())

            runtime.onLocalPromptStarted("again", emptyList(), emptyList())
            assertEquals(TurnStatus.WORKING, runtime.status())
        }

    @Test
    fun `a cancelled turn is unknown, never finished`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.TurnComplete("cancelled"))
            assertEquals(TurnStatus.UNKNOWN, runtime.status())

            runtime.onLocalPromptStarted("again", emptyList(), emptyList())
            runtime.onLocalCancel()
            assertEquals(TurnStatus.UNKNOWN, runtime.status())
        }

    @Test
    fun `refusal and turn limits are errors`() =
        runTest {
            val runtime = runtime()
            for (stopReason in listOf("refusal", "max_tokens", "max_turn_requests")) {
                runtime.onLocalPromptStarted("go", emptyList(), emptyList())
                runtime.onEvent(AppSessionEvent.TurnComplete(stopReason))
                assertEquals("stop reason $stopReason", TurnStatus.ERROR, runtime.status())
            }
        }

    @Test
    fun `an unrecognized or missing stop reason stays unknown`() =
        runTest {
            val runtime = runtime()
            for (stopReason in listOf("something-new", "unknown")) {
                runtime.onLocalPromptStarted("go", emptyList(), emptyList())
                runtime.onEvent(AppSessionEvent.TurnComplete(stopReason))
                assertEquals("stop reason $stopReason", TurnStatus.UNKNOWN, runtime.status())
            }
        }

    @Test
    fun `a failed prompt send is an error until the next turn`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onPromptSendFailed()

            assertEquals(TurnStatus.ERROR, runtime.status())
            runtime.onLocalPromptStarted("retry", emptyList(), emptyList())
            assertEquals(TurnStatus.WORKING, runtime.status())
        }

    @Test
    fun `a turn that ends drops a request it can no longer wait on`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.permissionRequested()
            assertEquals(TurnStatus.NEEDS_ATTENTION, runtime.status())

            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))
            assertEquals(TurnStatus.FINISHED, runtime.status())
        }

    @Test
    fun `prompt rollback preserves an acknowledged cancellation and clears attention`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.elicitationRequested()
            runtime.onLocalCancel()
            runtime.onPromptSendFailed()
            assertEquals(TurnStatus.UNKNOWN, runtime.status())

            runtime.onLocalPromptStarted("retry", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.TurnComplete("cancelled"))
            runtime.onPromptSendFailed()
            assertEquals(TurnStatus.UNKNOWN, runtime.status())

            runtime.onLocalPromptStarted("retry", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.TurnComplete("error"))
            runtime.onPromptSendFailed()
            assertEquals(TurnStatus.ERROR, runtime.status())
        }

    @Test
    fun `reattachment cannot reuse a retained terminal outcome`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))
            runtime.markReady()
            assertEquals(TurnStatus.UNKNOWN, runtime.status())

            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.TurnComplete("refusal"))
            runtime.onEvent(AppSessionEvent.SessionLoadComplete)
            assertEquals(TurnStatus.UNKNOWN, runtime.status())
        }

    @Test
    fun `hydration and load never manufacture a finished outcome`() =
        runTest {
            val runtime = runtime()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))
            assertEquals(TurnStatus.FINISHED, runtime.status())

            runtime.beginHydration()
            assertEquals(TurnStatus.UNKNOWN, runtime.status())
            runtime.completeHydration()
            assertEquals(TurnStatus.UNKNOWN, runtime.status())

            runtime.onEvent(AppSessionEvent.SessionLoadComplete)
            assertEquals(TurnStatus.UNKNOWN, runtime.status())

            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))
            runtime.failHydration("boom")
            assertEquals(TurnStatus.UNKNOWN, runtime.status())
        }
}
