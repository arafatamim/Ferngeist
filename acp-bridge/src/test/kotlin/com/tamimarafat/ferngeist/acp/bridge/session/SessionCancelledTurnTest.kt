package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ContentBlock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCancelledTurnTest {
    @Test
    fun cancelled_terminal_event_marks_the_stopped_reply() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.AgentMessage(contentBlock = ContentBlock.Text("partial")))
            runtime.onLocalCancel()
            runtime.onEvent(AppSessionEvent.TurnComplete("cancelled"))

            val reply =
                runtime.snapshot.value.messages
                    .last()
            assertEquals(true, reply.turn?.cancelled)
        }

    @Test
    fun cancelled_terminal_event_marks_a_late_tail_after_stop() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.AgentMessage(contentBlock = ContentBlock.Text("partial")))
            runtime.onLocalCancel()
            runtime.onEvent(AppSessionEvent.AgentMessage(contentBlock = ContentBlock.Text("late")))
            runtime.onEvent(AppSessionEvent.TurnComplete("cancelled"))

            val reply =
                runtime.snapshot.value.messages
                    .last()
            assertEquals(true, reply.turn?.cancelled)
        }

    @Test
    fun dropped_stream_ends_the_turn_without_marking_or_timing_the_reply() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.AgentMessage(contentBlock = ContentBlock.Text("partial")))
            runtime.onEvent(AppSessionEvent.TurnComplete(AppSessionEvent.STOP_REASON_DROPPED))

            val snapshot = runtime.snapshot.value
            val reply = snapshot.messages.last()
            assertEquals(false, snapshot.isStreaming)
            assertEquals(false, reply.cancelled)
            assertNull("the agent may still be running it, so no end time is known", reply.turn)
        }
}
