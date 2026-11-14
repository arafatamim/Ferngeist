@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.text
import com.tamimarafat.ferngeist.core.model.ChatResourceLink
import com.tamimarafat.ferngeist.core.model.content.chatFile
import com.tamimarafat.ferngeist.core.model.content.chatImage
import com.tamimarafat.ferngeist.core.model.content.chatResourceLink
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
        assertEquals(1, event.contentBlocks.mapNotNull { it.chatImage() }.size)
        assertEquals("QUJD", event.contentBlocks.mapNotNull { it.chatImage() }[0].base64)
        assertEquals("image/png", event.contentBlocks.mapNotNull { it.chatImage() }[0].mimeType)
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
        assertEquals(1, event.contentBlocks.mapNotNull { it.chatImage() }.size)
        assertEquals("QUJD", event.contentBlocks.mapNotNull { it.chatImage() }[0].base64)
        assertEquals("image/png", event.contentBlocks.mapNotNull { it.chatImage() }[0].mimeType)
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
        assertEquals(1, event.contentBlocks.mapNotNull { it.chatFile() }.size)
        assertEquals("report.pdf", event.contentBlocks.mapNotNull { it.chatFile() }[0].name)
        assertEquals("QUJD", event.contentBlocks.mapNotNull { it.chatFile() }[0].base64)
        assertEquals("application/pdf", event.contentBlocks.mapNotNull { it.chatFile() }[0].mimeType)
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
        assertEquals(1, event.contentBlocks.mapNotNull { it.chatFile() }.size)
        assertEquals("splash.zip", event.contentBlocks.mapNotNull { it.chatFile() }[0].name)
        assertEquals("application/zip", event.contentBlocks.mapNotNull { it.chatFile() }[0].mimeType)
        assertEquals("UEsDBAoAAAAA", event.contentBlocks.mapNotNull { it.chatFile() }[0].base64)
    }

    @Test
    fun `an inlined context block with an empty body yields a zero-byte file`() {
        val inlined =
            "see this\n" +
                "<context ref=\"file:///empty.txt\" mimeType=\"text/plain\" encoding=\"base64\"></context>"
        val update = SessionUpdate.UserMessageChunk(content = ContentBlock.Text(inlined))

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.UserMessage

        val file = event.contentBlocks.mapNotNull { it.chatFile() }.single()
        assertEquals("empty.txt", file.name)
        assertEquals(0L, file.sizeBytes)
    }

    @Test
    fun `tool call locations keep paths and lines`() {
        val locations =
            listOf(
                ToolCallLocation(path = "/repo/src/A.kt", line = 7u),
                ToolCallLocation(path = "/repo/src/B.kt"),
            )
        val update =
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool_123"),
                title = "Read",
                kind = ToolKind.READ,
                status = com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS,
                locations = locations,
            )

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.ToolCallStarted

        assertEquals(locations, event.locations)
    }

    @Test
    fun `tool call update locations keep paths and lines`() {
        val locations =
            listOf(
                ToolCallLocation(path = "/repo/src/A.kt", line = 11u),
                ToolCallLocation(path = "/repo/src/B.kt"),
            )
        val update =
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool_123"),
                status = com.agentclientprotocol.model.ToolCallStatus.COMPLETED,
                locations = locations,
            )

        val event = invokeMapSessionUpdateToEvent(update) as AppSessionEvent.ToolCallUpdated

        assertEquals(locations, event.locations)
    }

    private fun invokeMapSessionUpdateToEvent(update: SessionUpdate): AppSessionEvent =
        requireNotNull(AcpSessionUpdateMapper.mapSessionUpdateToEvent(update))

    @Test
    fun `a file resource link in a reply is a host file, not text`() {
        val event =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.AgentMessageChunk(
                    content =
                        ContentBlock.ResourceLink(
                            name = "Q3 review.txt",
                            uri = "file:///tmp/attach/Q3%20review.txt",
                            mimeType = "text/plain",
                            size = 58,
                        ),
                ),
            ) as AppSessionEvent.AgentMessage

        assertEquals("", event.text)
        assertEquals(
            ChatResourceLink("/tmp/attach/Q3 review.txt", "Q3 review.txt", "text/plain", 58),
            event.contentBlock.chatResourceLink(),
        )
    }

    @Test
    fun `an unencoded file uri keeps its spaces and non-ascii as written`() {
        val link =
            (
                invokeMapSessionUpdateToEvent(
                    SessionUpdate.AgentMessageChunk(
                        content = ContentBlock.ResourceLink(name = "", uri = "file:///tmp/Q3 review – ünï.txt"),
                    ),
                ) as AppSessionEvent.AgentMessage
            ).contentBlock.chatResourceLink()

        assertEquals("/tmp/Q3 review – ünï.txt", link?.path)
        assertEquals("Q3 review – ünï.txt", link?.name)
    }

    @Test
    fun `a resource link to another scheme stays an ordered content block`() {
        val event =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.AgentMessageChunk(
                    content = ContentBlock.ResourceLink(name = "spec [v2]", uri = "https://example.com/spec"),
                ),
            ) as AppSessionEvent.AgentMessage

        assertEquals(null, event.contentBlock.chatResourceLink())
        assertEquals(
            ContentBlock.ResourceLink(name = "spec [v2]", uri = "https://example.com/spec"),
            event.contentBlock,
        )
    }

    @Test
    fun `an embedded text resource stays separate from reply prose`() {
        val event =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.AgentMessageChunk(
                    content =
                        ContentBlock.Resource(
                            EmbeddedResourceResource.TextResourceContents(
                                text = "use ```go\nfmt.Println()\n```\n",
                                uri = "file:///tmp/notes.md",
                            ),
                        ),
                ),
            ) as AppSessionEvent.AgentMessage

        val resource = (event.contentBlock as ContentBlock.Resource).resource
        assertEquals(
            EmbeddedResourceResource.TextResourceContents(
                text = "use ```go\nfmt.Println()\n```\n",
                uri = "file:///tmp/notes.md",
            ),
            resource,
        )
    }

    @Test
    fun `an image in a reply survives as an image`() {
        val event =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.AgentMessageChunk(
                    content = ContentBlock.Image(data = "iVBORw0K", mimeType = "image/png"),
                ),
            ) as AppSessionEvent.AgentMessage

        assertEquals("iVBORw0K", event.contentBlock.chatImage()?.base64)
        assertEquals("", event.text)
    }

    @Test
    fun `a file the user linked rides on their message`() {
        val event =
            invokeMapSessionUpdateToEvent(
                SessionUpdate.UserMessageChunk(
                    content = ContentBlock.ResourceLink(name = "notes.md", uri = "file:///tmp/attach/notes.md"),
                ),
            ) as AppSessionEvent.UserMessage

        assertEquals(
            listOf("/tmp/attach/notes.md"),
            event.contentBlocks.mapNotNull { it.chatResourceLink() }.map { it.path },
        )
    }
}
