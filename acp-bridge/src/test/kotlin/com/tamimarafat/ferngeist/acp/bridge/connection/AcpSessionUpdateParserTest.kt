@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AcpSessionUpdateParserTest {
    @Test
    fun `usage_update maps used size and usd cost`() {
        val update =
            SessionUpdate.UsageUpdate(
                used = 2048,
                size = 8192,
                cost = com.agentclientprotocol.model.Cost(amount = 0.52, currency = "USD"),
            )

        val event = invokeMapSessionUpdateToEvent(update)
        val usage = event as AppSessionEvent.UsageUpdated
        assertEquals(2048L, usage.totalTokens)
        assertEquals(8192L, usage.contextWindowTokens)
        assertEquals(0.52, usage.costAmount ?: 0.0, 0.0001)
        assertEquals("USD", usage.costCurrency)
    }

    @Test
    fun `chunk text parser preserves whitespace-only text`() {
        val update =
            SessionUpdate.AgentMessageChunk(
                content = ContentBlock.Text("  \n"),
            )

        val event = invokeMapSessionUpdateToEvent(update)
        val msg = event as AppSessionEvent.AgentMessage
        assertEquals("  \n", msg.text)
    }

    @Test
    fun `message id maps through every content chunk`() {
        val messageId = MessageId("message_1")

        val user =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.UserMessageChunk(content = ContentBlock.Text("user"), messageId = messageId),
            ) as AppSessionEvent.UserMessage
        val agent =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("agent"), messageId = messageId),
            ) as AppSessionEvent.AgentMessage
        val thought =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.AgentThoughtChunk(content = ContentBlock.Text("thought"), messageId = messageId),
            ) as AppSessionEvent.AgentThought

        assertEquals("message_1", user.messageId)
        assertEquals("message_1", agent.messageId)
        assertEquals("message_1", thought.messageId)
    }

    @Test
    fun `tool call update maps rawOutput`() {
        val update =
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool_123"),
                title = "Read",
                rawOutput = kotlinx.serialization.json.JsonPrimitive("{\"ok\":true}"),
            )

        val event = invokeMapSessionUpdateToEvent(update)
        val tool = event as AppSessionEvent.ToolCallUpdated
        assertEquals("tool_123", tool.toolCallId)
        assertEquals("\"{\\\"ok\\\":true}\"", tool.rawOutput?.toString())
        assertNotNull(tool.title)
    }

    @Test
    fun `user message chunk with inline image data keeps the block`() {
        val update =
            SessionUpdate.UserMessageChunk(
                content = ContentBlock.Image(data = "QUJD", mimeType = "image/png"),
            )

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(ContentBlock.Image(data = "QUJD", mimeType = "image/png"), event.block)
    }

    @Test
    fun `user message chunk with data uri image falls back to uri payload`() {
        val update =
            SessionUpdate.UserMessageChunk(
                content =
                    ContentBlock.Image(
                        data = "",
                        mimeType = "",
                        uri = "data:image/png;base64,QUJD",
                    ),
            )

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(ContentBlock.Image(data = "QUJD", mimeType = "image/png"), event.block)
    }

    @Test
    fun `user message chunk with blob resource keeps the block`() {
        val content =
            ContentBlock.Resource(
                resource =
                    EmbeddedResourceResource.BlobResourceContents(
                        blob = "QUJD",
                        uri = "file:///report.pdf",
                        mimeType = "application/pdf",
                    ),
            )
        val update = SessionUpdate.UserMessageChunk(content = content)

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(content, event.block)
    }

    @Test
    fun `user message chunk with audio keeps the block`() {
        val content = ContentBlock.Audio(data = "QUJD", mimeType = "audio/wav")
        val update = SessionUpdate.UserMessageChunk(content = content)

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(content, event.block)
    }

    @Test
    fun `user message chunk with resource link keeps the block`() {
        val content =
            ContentBlock.ResourceLink(
                name = "report.pdf",
                uri = "file:///report.pdf",
                description = "Quarterly report",
                mimeType = "application/pdf",
                size = 42L,
                title = "Report",
            )
        val update = SessionUpdate.UserMessageChunk(content = content)

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(content, event.block)
    }

    @Test
    fun `user message chunk with text resource keeps the block`() {
        val content =
            ContentBlock.Resource(
                resource =
                    EmbeddedResourceResource.TextResourceContents(
                        text = "hello from a file",
                        uri = "file:///notes.txt",
                        mimeType = "text/plain",
                    ),
            )
        val update = SessionUpdate.UserMessageChunk(content = content)

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(content, event.block)
    }

    @Test
    fun `user message chunk with empty attachment payload yields no block`() {
        val update =
            SessionUpdate.UserMessageChunk(
                content = ContentBlock.Audio(data = "", mimeType = "audio/wav"),
            )

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(null, event.block)
    }

    private fun invokeMapSessionUpdateToEvent(update: SessionUpdate): AppSessionEvent =
        requireNotNull(AcpSessionUpdateMapper.mapSessionUpdateToEvent(update))
}
