@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.Annotations
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.SessionUpdate
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpSessionUpdateMapper
import com.tamimarafat.ferngeist.acp.bridge.session.persist.restore
import com.tamimarafat.ferngeist.acp.bridge.session.persist.savedState
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.core.model.audios
import com.tamimarafat.ferngeist.core.model.content
import com.tamimarafat.ferngeist.core.model.content.userContentBlocks
import com.tamimarafat.ferngeist.core.model.promptResourceLinks
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserContentReplayTest {
    @Test
    fun load_preserves_mixed_content_order_without_polluting_prompt_text() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            val blocks = representativeContent()
            runtime.beginHydration()
            blocks.forEach { block ->
                val update = SessionUpdate.UserMessageChunk(content = block)
                runtime.onEvent(requireNotNull(AcpSessionUpdateMapper.mapSessionUpdateToEvent(update)))
            }
            runtime.completeHydration()

            val user =
                runtime.snapshot.value.messages
                    .single()
            assertEquals("before before after", user.content)
            assertEquals(blocks, user.contentBlocks)
            val restored = SessionRuntime("restored")
            restored.restore(runtime.savedState())
            assertEquals(
                blocks,
                restored.snapshot.value.messages
                    .single()
                    .contentBlocks,
            )
        }

    @Test
    fun audio_text_resources_and_links_survive_the_optimistic_prompt_and_a_restore() =
        runTest {
            val runtime = SessionRuntime("ses_test")
            val file =
                ChatFileData(
                    name = "notes.txt",
                    base64 = "aGVsbG8=",
                    mimeType = "text/plain",
                    sizeBytes = 5,
                )
            val audio = ChatAudioData(base64 = "YXVkaW8=", mimeType = "audio/wav", name = "voice.wav", sizeBytes = 5)
            val link = ChatPromptResourceLink("main.kt", "file:///workspace/main.kt")
            runtime.onLocalPromptStarted(
                "hello",
                emptyList(),
                listOf(file),
                clientId = "local-1",
                audios = listOf(audio),
                resourceLinks = listOf(link),
            )
            val sent =
                runtime.snapshot.value.messages
                    .first { it.role == ChatMessage.Role.USER }
                    .contentBlocks

            val restored = SessionRuntime("restored")
            restored.restore(runtime.savedState())

            assertEquals(
                sent,
                restored.snapshot.value.messages
                    .first { it.role == ChatMessage.Role.USER }
                    .contentBlocks,
            )
            assertEquals(
                "YXVkaW8=",
                restored.snapshot.value.messages
                    .first()
                    .audios
                    .single()
                    .base64,
            )
            assertEquals(
                "audio/wav",
                restored.snapshot.value.messages
                    .first()
                    .audios
                    .single()
                    .mimeType,
            )
            assertEquals(
                listOf(link),
                restored.snapshot.value.messages
                    .first()
                    .promptResourceLinks,
            )
        }

    @Test
    fun repeated_echo_text_chunks_stay_content_instead_of_deduping_away() =
        runTest {
            val runtime = SessionRuntime("ses_test")
            runtime.beginHydration()
            repeat(2) {
                runtime.onEvent(
                    AppSessionEvent.UserMessage(
                        contentBlocks = userContentBlocks("echo", emptyList(), emptyList()),
                        append = true,
                        clientId = "prompt-1",
                    ),
                )
            }
            runtime.completeHydration()

            val restored = SessionRuntime("restored")
            restored.restore(runtime.savedState())
            val message =
                restored.snapshot.value.messages
                    .single()
            assertEquals("echoecho", message.content)
            assertEquals(
                listOf("echo", "echo"),
                message.contentBlocks.map { (it as ContentBlock.Text).text },
            )
        }

    @Test
    fun mixed_assistant_blocks_survive_load_and_save_in_arrival_order() =
        runTest {
            val runtime = SessionRuntime("ses_test")
            val blocks =
                listOf(
                    ContentBlock.Text("a", annotations = Annotations(priority = 1.0), _meta = JsonPrimitive("one")),
                    ContentBlock.Text("b", annotations = Annotations(priority = 1.0), _meta = JsonPrimitive("two")),
                    ContentBlock.Text(
                        "c",
                        annotations = Annotations(lastModified = "2026-11-14"),
                        _meta = JsonPrimitive("two"),
                    ),
                ) + representativeContent() +
                    listOf(
                        ContentBlock.Text(
                            "d",
                            annotations = Annotations(priority = 1.0),
                            _meta = JsonPrimitive("shared"),
                        ),
                        ContentBlock.Text(
                            "e",
                            annotations = Annotations(priority = 1.0),
                            _meta = JsonPrimitive("shared"),
                        ),
                    )
            runtime.beginHydration()
            blocks.forEach { block ->
                runtime.onEvent(
                    requireNotNull(
                        AcpSessionUpdateMapper.mapSessionUpdateToEvent(
                            SessionUpdate.AgentMessageChunk(content = block),
                        ),
                    ),
                )
            }
            runtime.completeHydration()
            val restored = SessionRuntime("restored")
            restored.restore(runtime.savedState())
            val message =
                restored.snapshot.value.messages
                    .single()
            assertEquals(
                blocks.dropLast(2) +
                    ContentBlock.Text(
                        "de",
                        annotations = Annotations(priority = 1.0),
                        _meta = JsonPrimitive("shared"),
                    ),
                message.segments.map { it.contentBlock },
            )
            assertEquals("abcbefore before afterde", message.content)
        }

    @Test
    fun echo_claims_the_optimistic_prompt_independently_of_delivery_acknowledgement_and_optional_message_id() =
        runTest {
            val image = ChatImageData("aW1n", "image/png")
            val file = ChatFileData("a.txt", "YQ==", "text/plain", 1)
            val blocks = userContentBlocks("before", listOf(image), listOf(file))
            listOf(null, "user-1").forEach { messageId ->
                val runtime = SessionRuntime("ses_test")
                runtime.onLocalPromptStarted("before", listOf(image), listOf(file), clientId = "local-1")
                // Delivery acknowledgement is independent of the echo's first content chunk.
                runtime.onEvent(AppSessionEvent.PromptDelivered("local-1"))
                blocks.forEach { block ->
                    runtime.onEvent(
                        AppSessionEvent.UserMessage(
                            contentBlocks = listOf(block),
                            append = true,
                            clientId = "local-1",
                            messageId = messageId,
                        ),
                    )
                }
                val restored = SessionRuntime("restored")
                restored.restore(runtime.savedState())
                val (prompt, placeholder) = restored.snapshot.value.messages
                assertEquals(blocks, prompt.contentBlocks)
                assertEquals(ChatMessage.Role.ASSISTANT, placeholder.role)
                assertTrue(placeholder.isStreaming)
                assertEquals("local-1", prompt.clientId)
                assertEquals(messageId, prompt.messageId)
                assertTrue(prompt.deliveryConfirmed)
            }
        }

    @Test
    fun load_normalization_keeps_context_files_between_their_surrounding_text() =
        runTest {
            val runtime = SessionRuntime("ses_test")
            val annotations = Annotations(priority = 1.0)
            val metadata = JsonPrimitive("attachment-context")
            val wire =
                listOf(
                    ContentBlock.Text(
                        "A<context ref=\"file:///a.txt\" mimeType=\"text/plain\" encoding=\"base64\">YQ==</context>B",
                        annotations = annotations,
                        _meta = metadata,
                    ),
                    ContentBlock.Image(data = "", mimeType = "", uri = "data:image/png;base64,aW1n"),
                )
            runtime.beginHydration()
            wire.forEach {
                runtime.onEvent(
                    requireNotNull(AcpSessionUpdateMapper.mapSessionUpdateToEvent(SessionUpdate.UserMessageChunk(it))),
                )
            }
            runtime.completeHydration()
            val message =
                runtime.snapshot.value.messages
                    .single()
            assertEquals("AB", message.content)
            assertEquals(
                listOf(
                    ContentBlock.Text("A", annotations = annotations, _meta = metadata),
                    ContentBlock.Resource(
                        EmbeddedResourceResource.BlobResourceContents("YQ==", "file:///a.txt", "text/plain"),
                        annotations = annotations,
                        _meta = metadata,
                    ),
                    ContentBlock.Text("B", annotations = annotations, _meta = metadata),
                    ContentBlock.Image(data = "aW1n", mimeType = "image/png", uri = "data:image/png;base64,aW1n"),
                ),
                message.contentBlocks,
            )
        }

    private fun representativeContent(): List<ContentBlock> =
        listOf(
            ContentBlock.Text("before "),
            ContentBlock.Image(data = "aW1n", mimeType = "image/png"),
            ContentBlock.Text("before "),
            ContentBlock.Audio(data = "YXVkaW8=", mimeType = "audio/wav"),
            ContentBlock.ResourceLink(name = "host.txt", uri = "file:///host.txt"),
            ContentBlock.Audio(data = "YXVkaW8=", mimeType = "audio/wav"),
            ContentBlock.ResourceLink(name = "spec", uri = "https://example.com/spec"),
            ContentBlock.Text("after"),
            ContentBlock.Resource(
                EmbeddedResourceResource.TextResourceContents(
                    text = "embedded body",
                    uri = "file:///notes.txt",
                    mimeType = "text/plain",
                ),
            ),
            ContentBlock.Resource(
                EmbeddedResourceResource.BlobResourceContents(
                    blob = "UERG",
                    uri = "file:///report.pdf",
                    mimeType = "application/pdf",
                ),
            ),
        )
}
