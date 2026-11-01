package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A runtime's state saved by one process and restored in the next. */
class SessionRuntimeRestoreTest {
    @Test
    fun a_restored_mid_turn_state_continues_exactly_like_the_saved_runtime() =
        runTest {
            val original = SessionRuntime(sessionId = "ses_test")
            listOf(
                AppSessionEvent.ModesUpdated(listOf(SessionMode("code", "Code")), currentModeId = "code"),
                AppSessionEvent.ConfigOptionsUpdated(
                    listOf(SessionConfigOption.BooleanOption(id = "fast", name = "Fast", currentValue = true)),
                ),
                AppSessionEvent.CommandsUpdated(listOf(CommandInfo("review"))),
                AppSessionEvent.UsageUpdated(totalTokens = 42),
            ).forEach { original.onEvent(it) }
            original.onLocalPromptStarted("go", emptyList(), emptyList())
            original.onEvent(AppSessionEvent.AgentThought("thinking"))
            original.onEvent(
                AppSessionEvent.ToolCallStarted(
                    "c1",
                    "edit",
                    ToolKind.EDIT,
                    ToolCallStatus.IN_PROGRESS,
                    content = listOf(ToolCallContent.Diff(path = "a.kt", newText = "new")),
                ),
            )

            val restored = SessionRuntime(sessionId = "ses_test")
            restored.restore(original.savedState())
            // A monotonic time mark cannot cross processes, so the restored turn is untimed.
            assertEquals(original.snapshot.value.copy(turnStatusSince = null), restored.snapshot.value)
            assertTrue(restored.snapshot.value.isStreaming)

            listOf(
                AppSessionEvent.ToolCallUpdated("c1", ToolCallStatus.COMPLETED, null, null),
                AppSessionEvent.AgentMessage("done"),
                AppSessionEvent.TurnComplete("end_turn", durationMs = 5_000L),
            ).forEach { event ->
                original.onEvent(event)
                restored.onEvent(event)
            }
            // Segments the shared events created carry fresh random ids in each runtime, and the
            // completion sequence and time marks are each runtime's own.
            assertEquals(original.snapshot.value.withoutLocalIds(), restored.snapshot.value.withoutLocalIds())
            assertEquals(
                ToolCallStatus.COMPLETED,
                restored.snapshot.value.messages
                    .toolCall("c1")
                    ?.status,
            )
        }

    @Test
    fun a_restored_permission_prompt_waits_for_the_gateway_to_ask_again() =
        runTest {
            val original = SessionRuntime(sessionId = "ses_test")
            original.onLocalPromptStarted("go", emptyList(), emptyList())
            original.onEvent(AppSessionEvent.ToolCallStarted("c1", "rm", ToolKind.EXECUTE, ToolCallStatus.PENDING))
            val option = SessionPermissionOption("allow", "Allow")
            original.onEvent(AppSessionEvent.ToolPermissionRequested("c1", "r1", "rm", listOf(option)))

            val restored = SessionRuntime(sessionId = "ses_test")
            restored.restore(original.savedState())
            // The saved request died with the process: answering it would reach nobody.
            assertEquals(
                null,
                restored.snapshot.value.messages
                    .toolCall("c1")
                    ?.permissionOptions,
            )
            assertEquals(TurnStatus.WORKING, restored.snapshot.value.turnStatus)

            restored.onEvent(AppSessionEvent.ToolPermissionRequested("c1", "r2", "rm", listOf(option)))
            assertEquals(
                "r2",
                restored.snapshot.value.messages
                    .toolCall("c1")
                    ?.permissionRequestId,
            )
            assertEquals(TurnStatus.NEEDS_ATTENTION, restored.snapshot.value.turnStatus)
        }

    private fun List<ChatMessage>.toolCall(id: String) =
        flatMap { it.segments }.firstNotNullOfOrNull { segment -> segment.toolCall?.takeIf { it.toolCallId == id } }

    private fun SessionSnapshot.withoutLocalIds() =
        copy(
            turnCompletionSequence = 0,
            turnStatusSince = null,
            messages = messages.map { m -> m.copy(segments = m.segments.map { it.copy(id = "") }.toPersistentList()) },
        )
}
