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
    fun `message id maps through user and agent message chunks`() {
        val messageId = MessageId("message_1")

        val user =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.UserMessageChunk(content = ContentBlock.Text("user"), messageId = messageId),
            ) as AppSessionEvent.UserMessage
        val agent =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("agent"), messageId = messageId),
            ) as AppSessionEvent.AgentMessage

        assertEquals("message_1", user.messageId)
        assertEquals("message_1", agent.messageId)
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
    fun `user message chunk with inline image data yields image`() {
        val update =
            SessionUpdate.UserMessageChunk(
                content = ContentBlock.Image(data = "QUJD", mimeType = "image/png"),
            )

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(1, event.images.size)
        assertEquals("QUJD", event.images[0].base64)
        assertEquals("image/png", event.images[0].mimeType)
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
        assertEquals(1, event.images.size)
        assertEquals("QUJD", event.images[0].base64)
        assertEquals("image/png", event.images[0].mimeType)
    }

    @Test
    fun `user message chunk with blob resource yields file`() {
        val update =
            SessionUpdate.UserMessageChunk(
                content =
                    ContentBlock.Resource(
                        resource =
                            EmbeddedResourceResource.BlobResourceContents(
                                blob = "QUJD",
                                uri = "file:///report.pdf",
                                mimeType = "application/pdf",
                            ),
                    ),
            )

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage
        assertEquals(1, event.files.size)
        assertEquals("report.pdf", event.files[0].name)
        assertEquals("QUJD", event.files[0].base64)
        assertEquals("application/pdf", event.files[0].mimeType)
    }

    @Test
    fun `user message text with an inlined base64 context block yields a file and keeps the prose`() {
        val inlined =
            "[@splash.zip](file:///splash.zip)\n" +
                "<context ref=\"file:///splash.zip\" mimeType=\"application/zip\" encoding=\"base64\">\n" +
                "UEsDBAoA\nAAAA\n</context>\nwhy does this crash?"
        val update = SessionUpdate.UserMessageChunk(content = ContentBlock.Text(inlined))

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage

        assertEquals("why does this crash?", event.text)
        assertEquals(1, event.files.size)
        assertEquals("splash.zip", event.files[0].name)
        assertEquals("application/zip", event.files[0].mimeType)
        assertEquals("UEsDBAoAAAAA", event.files[0].base64)
    }

    private fun invokeMapSessionUpdateToEvent(update: SessionUpdate): AppSessionEvent =
        requireNotNull(AcpSessionUpdateMapper.mapSessionUpdateToEvent(update))
}
