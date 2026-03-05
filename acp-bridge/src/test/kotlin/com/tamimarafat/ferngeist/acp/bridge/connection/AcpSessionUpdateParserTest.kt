package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AcpSessionUpdateParserTest {

    @Test
    fun `usage_update maps used size and usd cost`() {
        val manager = AcpConnectionManager(TestConnectivityObserver(), CoroutineScope(Dispatchers.Unconfined))
        val update = SessionUpdate.UsageUpdate(
            used = 2048,
            size = 8192,
            cost = com.agentclientprotocol.model.Cost(amount = 0.52, currency = "USD")
        )

        val event = invokeMapSessionUpdateToEvent(manager, update)
        val usage = event as AppSessionEvent.UsageUpdated
        assertEquals(2048, usage.totalTokens)
        assertEquals(8192, usage.contextWindowTokens)
        assertEquals(0.52, usage.costUsd ?: 0.0, 0.0001)
    }

    @Test
    fun `chunk text parser preserves whitespace-only text`() {
        val manager = AcpConnectionManager(TestConnectivityObserver(), CoroutineScope(Dispatchers.Unconfined))
        val update = SessionUpdate.AgentMessageChunk(
            content = ContentBlock.Text("  \n")
        )

        val event = invokeMapSessionUpdateToEvent(manager, update)
        val msg = event as AppSessionEvent.AgentMessage
        assertEquals("  \n", msg.text)
    }

    @Test
    fun `tool call update maps rawOutput`() {
        val manager = AcpConnectionManager(TestConnectivityObserver(), CoroutineScope(Dispatchers.Unconfined))
        val update = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tool_123"),
            title = "Read",
            rawOutput = kotlinx.serialization.json.JsonPrimitive("{\"ok\":true}")
        )

        val event = invokeMapSessionUpdateToEvent(manager, update)
        val tool = event as AppSessionEvent.ToolCallUpdated
        assertEquals("tool_123", tool.toolCallId)
        assertEquals("\"{\\\"ok\\\":true}\"", tool.rawOutput)
        assertNotNull(tool.title)
    }

    private fun invokeMapSessionUpdateToEvent(
        manager: AcpConnectionManager,
        update: SessionUpdate,
    ): AppSessionEvent {
        val method = AcpConnectionManager::class.java.getDeclaredMethod(
            "mapSessionUpdateToEvent",
            SessionUpdate::class.java
        )
        method.isAccessible = true
        return method.invoke(manager, update) as AppSessionEvent
    }
}

private class TestConnectivityObserver : ConnectivityObserver {
    override val isConnected: Flow<Boolean> = MutableStateFlow(true)
}
