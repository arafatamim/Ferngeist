package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AcpSessionUpdateParserTest {

    @Test
    fun `usage_update maps used size and usd cost`() {
        val update = SessionUpdate.UsageUpdate(
            used = 2048,
            size = 8192,
            cost = com.agentclientprotocol.model.Cost(amount = 0.52, currency = "USD")
        )

        val event = invokeMapSessionUpdateToEvent(update)
        val usage = event as AppSessionEvent.UsageUpdated
        assertEquals(2048, usage.totalTokens)
        assertEquals(8192, usage.contextWindowTokens)
        assertEquals(0.52, usage.costUsd ?: 0.0, 0.0001)
    }

    @Test
    fun `chunk text parser preserves whitespace-only text`() {
        val update = SessionUpdate.AgentMessageChunk(
            content = ContentBlock.Text("  \n")
        )

        val event = invokeMapSessionUpdateToEvent(update)
        val msg = event as AppSessionEvent.AgentMessage
        assertEquals("  \n", msg.text)
    }

    @Test
    fun `tool call update maps rawOutput`() {
        val update = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tool_123"),
            title = "Read",
            rawOutput = kotlinx.serialization.json.JsonPrimitive("{\"ok\":true}")
        )

        val event = invokeMapSessionUpdateToEvent(update)
        val tool = event as AppSessionEvent.ToolCallUpdated
        assertEquals("tool_123", tool.toolCallId)
        assertEquals("\"{\\\"ok\\\":true}\"", tool.rawOutput)
        assertNotNull(tool.title)
    }

    private fun invokeMapSessionUpdateToEvent(
        update: SessionUpdate,
    ): AppSessionEvent {
        return requireNotNull(AcpSessionUpdateMapper.mapSessionUpdateToEvent(update))
    }
}
