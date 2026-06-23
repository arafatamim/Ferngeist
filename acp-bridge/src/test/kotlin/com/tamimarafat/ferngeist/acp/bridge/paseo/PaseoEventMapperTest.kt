package com.tamimarafat.ferngeist.acp.bridge.paseo

import com.agentclientprotocol.model.ToolCallStatus
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the Paseo `AgentStreamEvent` / timeline-item → [AppSessionEvent] translation. */
class PaseoEventMapperTest {
    private fun obj(json: String): JsonObject = PaseoJson.parseToJsonElement(json).jsonObject

    @Test
    fun `assistant message timeline maps to AgentMessage`() {
        val events =
            PaseoEventMapper.mapStreamEvent(
                obj("""{"type":"timeline","item":{"type":"assistant_message","text":"hi"}}"""),
            )
        assertEquals(1, events.size)
        val event = events.first()
        assertTrue(event is AppSessionEvent.AgentMessage)
        assertEquals("hi", (event as AppSessionEvent.AgentMessage).text)
    }

    @Test
    fun `user message timeline maps to UserMessage`() {
        val events =
            PaseoEventMapper.mapTimelineItem(obj("""{"type":"user_message","text":"hello"}"""))
        assertEquals("hello", (events.single() as AppSessionEvent.UserMessage).text)
    }

    @Test
    fun `reasoning maps to AgentThought`() {
        val events = PaseoEventMapper.mapTimelineItem(obj("""{"type":"reasoning","text":"thinking"}"""))
        assertTrue(events.single() is AppSessionEvent.AgentThought)
    }

    @Test
    fun `running tool call maps to ToolCallStarted in progress`() {
        val events =
            PaseoEventMapper.mapTimelineItem(
                obj("""{"type":"tool_call","callId":"c1","name":"Read","status":"running"}"""),
            )
        val started = events.single() as AppSessionEvent.ToolCallStarted
        assertEquals("c1", started.toolCallId)
        assertEquals("Read", started.title)
        assertEquals(ToolCallStatus.IN_PROGRESS, started.status)
    }

    @Test
    fun `completed tool call maps to ToolCallUpdated completed`() {
        val events =
            PaseoEventMapper.mapTimelineItem(
                obj("""{"type":"tool_call","callId":"c2","name":"Bash","status":"completed"}"""),
            )
        val updated = events.single() as AppSessionEvent.ToolCallUpdated
        assertEquals(ToolCallStatus.COMPLETED, updated.status)
    }

    @Test
    fun `turn completed emits usage then TurnComplete`() {
        val events =
            PaseoEventMapper.mapStreamEvent(
                obj("""{"type":"turn_completed","usage":{"totalTokens":42,"inputTokens":10}}"""),
            )
        assertTrue(events.any { it is AppSessionEvent.UsageUpdated && it.totalTokens == 42 })
        assertTrue(events.any { it is AppSessionEvent.TurnComplete })
    }

    @Test
    fun `turn failed maps to TurnComplete error`() {
        val events = PaseoEventMapper.mapStreamEvent(obj("""{"type":"turn_failed","error":"boom"}"""))
        assertEquals("error", (events.single() as AppSessionEvent.TurnComplete).stopReason)
    }

    @Test
    fun `unknown event type yields no events`() {
        assertTrue(PaseoEventMapper.mapStreamEvent(obj("""{"type":"some_future_event"}""")).isEmpty())
    }
}
