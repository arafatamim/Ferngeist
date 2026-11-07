@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.SessionUpdate
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpSessionUpdateMapper
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UserContentReplayTest {
    @Test
    fun load_replay_preserves_every_acp_user_content_block_in_order() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()

            val content = representativeUserContent()
            replay(runtime, content)
            runtime.onEvent(AppSessionEvent.AgentMessage("done"))
            runtime.completeHydration()

            val (user, assistant) = runtime.snapshot.value.messages
            assertEquals(ChatMessage.Role.USER, user.role)
            assertEquals(content, user.contentBlocks)
            assertEquals("Review: the spec and notes", user.content)
            assertEquals(ChatMessage.Role.ASSISTANT, assistant.role)
            assertEquals("done", assistant.segments.single().text)
        }

    @Test
    fun load_replay_keeps_text_between_attachments_in_place() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()

            val content =
                listOf(
                    ContentBlock.Text("before "),
                    ContentBlock.Audio(data = "YXVkaW8=", mimeType = "audio/wav"),
                    ContentBlock.Text("after"),
                )
            replay(runtime, content)
            runtime.completeHydration()

            val messages = runtime.snapshot.value.messages
            val user = messages.single()
            assertEquals(content, user.contentBlocks)
            assertEquals("before after", user.content)
        }

    private suspend fun replay(
        runtime: SessionRuntime,
        contentBlocks: List<ContentBlock>,
    ) {
        contentBlocks.forEach { block ->
            val update = SessionUpdate.UserMessageChunk(content = block)
            runtime.onEvent(requireNotNull(AcpSessionUpdateMapper.mapSessionUpdateToEvent(update)))
        }
    }

    private fun representativeUserContent(): List<ContentBlock> =
        listOf(
            ContentBlock.Text("Review: "),
            ContentBlock.Image(data = "aW1n", mimeType = "image/png"),
            ContentBlock.Text("the spec "),
            ContentBlock.Resource(
                resource =
                    EmbeddedResourceResource.BlobResourceContents(
                        blob = "UERG",
                        uri = "file:///report.pdf",
                        mimeType = "application/pdf",
                    ),
            ),
            ContentBlock.Audio(data = "YXVkaW8=", mimeType = "audio/wav"),
            ContentBlock.Text("and notes"),
            ContentBlock.ResourceLink(
                name = "spec.md",
                uri = "file:///spec.md",
                description = "The spec",
                mimeType = "text/markdown",
                size = 12L,
                title = "Spec",
            ),
            ContentBlock.Resource(
                resource =
                    EmbeddedResourceResource.TextResourceContents(
                        text = "embedded body",
                        uri = "file:///notes.txt",
                        mimeType = "text/plain",
                    ),
            ),
        )
}
