package com.tamimarafat.ferngeist.acp.bridge.session.lifecycle

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionRuntime
import com.tamimarafat.ferngeist.core.model.content.userContentBlocks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionToolFinalizationTest {
    @Test
    fun a_turn_end_fails_unfinished_tool_calls_even_before_a_steer() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()
            runtime.completeHydration()
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            val calls =
                listOf(
                    "done" to ToolCallStatus.COMPLETED,
                    "running" to ToolCallStatus.IN_PROGRESS,
                    "pending" to ToolCallStatus.PENDING,
                )
            for ((id, status) in calls) {
                runtime.onEvent(AppSessionEvent.ToolCallStarted(id, id, ToolKind.EXECUTE, status))
            }
            runtime.onPromptSteered("nudge", emptyList(), emptyList(), clientId = null, injected = true)
            assertEquals(calls.map { it.second }, runtime.toolStatuses())
            // A terminal event also closes tools above the steer, even if their updates never arrived.
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))

            assertEquals(
                listOf(ToolCallStatus.COMPLETED, ToolCallStatus.FAILED, ToolCallStatus.FAILED),
                runtime.toolStatuses(),
            )
        }

    @Test
    fun hydration_fails_historical_tools_only_when_no_turn_is_running() =
        runTest {
            val cases = listOf(false to ToolCallStatus.FAILED, true to ToolCallStatus.IN_PROGRESS)
            for ((running, expected) in cases) {
                val runtime = SessionRuntime(sessionId = "ses_test")
                runtime.beginHydration()
                runtime.onEvent(
                    AppSessionEvent.UserMessage(
                        contentBlocks = userContentBlocks("old", emptyList(), emptyList()),
                        append = true,
                    ),
                )
                runtime.onEvent(
                    AppSessionEvent.ToolCallStarted("c1", "read", ToolKind.READ, ToolCallStatus.IN_PROGRESS),
                )
                runtime.onEvent(
                    AppSessionEvent.UserMessage(
                        contentBlocks = userContentBlocks("new", emptyList(), emptyList()),
                        append = true,
                    ),
                )
                runtime.onEvent(
                    AppSessionEvent.ToolCallStarted("c2", "read", ToolKind.READ, ToolCallStatus.IN_PROGRESS),
                )

                runtime.completeHydration(turnRunning = running)

                assertEquals("turnRunning=$running", listOf(expected, expected), runtime.toolStatuses())
            }
        }

    @Test
    fun attach_fails_unfinished_tools_only_when_the_gateway_confirms_an_idle_turn() =
        runTest {
            val cases = listOf(false to ToolCallStatus.FAILED, true to ToolCallStatus.IN_PROGRESS)
            for ((running, expected) in cases) {
                val runtime = SessionRuntime(sessionId = "ses_test")
                runtime.onLocalPromptStarted("go", emptyList(), emptyList())
                runtime.onEvent(
                    AppSessionEvent.ToolCallStarted("c1", "read", ToolKind.READ, ToolCallStatus.IN_PROGRESS),
                )
                runtime.beginAttach()

                runtime.settleAttach(turnRunning = running)

                assertEquals("turnRunning=$running", listOf(expected), runtime.toolStatuses())
            }
        }

    @Test
    fun an_unheard_close_does_not_prove_that_running_tools_failed() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.onLocalPromptStarted("go", emptyList(), emptyList())
            runtime.onEvent(AppSessionEvent.ToolCallStarted("c1", "read", ToolKind.READ, ToolCallStatus.IN_PROGRESS))

            // deliveredOnce uses this close even when the gateway's original turn is still running.
            runtime.onEvent(AppSessionEvent.TurnEndUnheard)

            assertEquals(listOf(ToolCallStatus.IN_PROGRESS), runtime.toolStatuses())
        }

    private fun SessionRuntime.toolStatuses() =
        snapshot.value.messages
            .flatMap { it.segments }
            .mapNotNull { it.toolCall }
            .map { it.status }
}
