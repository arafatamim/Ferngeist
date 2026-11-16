package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ContentBlock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
