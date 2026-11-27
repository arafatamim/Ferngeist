package com.tamimarafat.ferngeist.acp.bridge.hub

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.content
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTurnsTest {
    @Test
    fun `live turns carry the plan and activity of the turn's own bubble`() =
        runTest {
            val hub = newHub()
            hub.registerChat(
                sessionId = "s1",
                snapshot = MutableStateFlow(snapshot(TurnStatus.WORKING, liveBubble())),
            )

            val turn = hub.liveTurns.value.single()
            assertEquals("s1", turn.presence.sessionId)
            assertEquals(listOf("a", "b"), turn.planEntries.map { it.content })
            assertEquals("Editing Login.kt", turn.activity)
        }

    @Test
    fun `a turn with an outcome remains observable to the notification`() =
        runTest {
            val hub = newHub()
            val state = MutableStateFlow(snapshot(TurnStatus.WORKING, liveBubble()))
            hub.registerChat(sessionId = "s1", snapshot = state)

            state.value = state.value.copy(turnStatus = TurnStatus.FINISHED, isStreaming = false)

            val result = hub.liveTurns.value.single()
            assertEquals(TurnStatus.FINISHED, result.status)
        }

    @Test
    fun `a terminal sequence restored at attachment is not a new outcome`() =
        runTest {
            val hub = newHub()
            hub.registerChat(sessionId = "s1", snapshot = MutableStateFlow(restored()))

            assertNull(outcome(hub))
        }

    @Test
    fun `a terminal sequence after attachment is new even without observing work`() =
        runTest {
            val hub = newHub()
            val state = MutableStateFlow(restored())
            hub.registerChat(sessionId = "s1", snapshot = state)

            state.value = restored().copy(turnCompletionSequence = 8)

            assertNotNull(outcome(hub))
        }

    @Test
    fun `re-registering the same snapshot flow keeps the observed outcome`() =
        runTest {
            val hub = newHub()
            val state = MutableStateFlow(restored())
            hub.registerChat(sessionId = "s1", snapshot = state)
            state.value = restored().copy(turnCompletionSequence = 8)
            val observed = outcome(hub)

            hub.registerChat(sessionId = "s1", snapshot = state)

            assertEquals(observed, outcome(hub))
        }

    @Test
    fun `re-registering with a new snapshot flow takes a fresh baseline`() =
        runTest {
            val hub = newHub()
            val state = MutableStateFlow(restored())
            hub.registerChat(sessionId = "s1", snapshot = state)
            state.value = restored().copy(turnCompletionSequence = 8)

            hub.registerChat(sessionId = "s1", snapshot = MutableStateFlow(state.value))

            assertNull(outcome(hub))
        }

    @Test
    fun `a closed bubble does not leak its plan into the next turn`() =
        runTest {
            val hub = newHub()
            hub.registerChat(
                sessionId = "s1",
                snapshot = MutableStateFlow(snapshot(TurnStatus.WORKING, liveBubble().copy(isStreaming = false))),
            )

            val turn = hub.liveTurns.value.single()
            assertTrue(turn.planEntries.isEmpty())
        }

    @Test
    fun `a pending request keeps its turn live as needing attention`() =
        runTest {
            val hub = newHub()
            hub.registerChat(
                sessionId = "s1",
                snapshot = MutableStateFlow(snapshot(TurnStatus.NEEDS_ATTENTION, liveBubble())),
            )

            val turn = hub.liveTurns.value.single()
            assertEquals(TurnStatus.NEEDS_ATTENTION, turn.status)
        }

    @Test
    fun `an attached turn with no known outcome still reports that its request ended`() =
        runTest {
            val hub = newHub()
            val state = MutableStateFlow(snapshot(TurnStatus.NEEDS_ATTENTION, liveBubble()))
            hub.registerChat(sessionId = "s1", snapshot = state)

            state.value = snapshot(TurnStatus.UNKNOWN, null)

            val settled = hub.liveTurns.value.single()
            assertEquals(TurnStatus.UNKNOWN, settled.status)
            assertNull(settled.outcomeId)
        }

    private fun restored() = snapshot(TurnStatus.FINISHED, null).copy(turnCompletionSequence = 7)

    private fun outcome(hub: ChatConnectionHub) =
        hub.liveTurns.value
            .single()
            .outcomeId

    private fun snapshot(
        status: TurnStatus,
        message: ChatMessage?,
    ) = SessionSnapshot(
        sessionId = "s1",
        loadState = SessionLoadState.READY,
        messages = listOfNotNull(message),
        isStreaming = status == TurnStatus.WORKING,
        turnStatus = status,
    )

    private fun liveBubble(): ChatMessage {
        val plan =
            AssistantSegment(
                kind = AssistantSegment.Kind.PLAN,
                planEntries =
                    listOf(
                        entry("a", PlanEntryStatus.COMPLETED),
                        entry("b", PlanEntryStatus.IN_PROGRESS),
                    ),
            )
        val editing =
            AssistantSegment(
                kind = AssistantSegment.Kind.TOOL_CALL,
                toolCall = ToolCallDisplay(title = "Editing Login.kt"),
            )
        return ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            isStreaming = true,
            segments = persistentListOf(plan, editing),
        )
    }

    private fun entry(
        content: String,
        status: PlanEntryStatus,
    ) = PlanEntry(content = content, priority = PlanEntryPriority.MEDIUM, status = status)
}
