package com.tamimarafat.ferngeist.core.model.content

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class UserContentBlocksTest {
    @Test
    fun `audio, embedded text and resource links use their ACP block types`() {
        val textFile =
            ChatFileData(
                name = "notes.txt",
                base64 = Base64.getEncoder().encodeToString("hello".toByteArray(StandardCharsets.UTF_8)),
                mimeType = "text/plain",
                sizeBytes = 5,
            )
        val link =
            ChatPromptResourceLink(name = "main.kt", uri = "file:///workspace/main.kt", mimeType = "text/x-kotlin")

        val blocks =
            userContentBlocks(
                text = "prompt",
                images = listOf(ChatImageData("aW1n", "image/png")),
                files = listOf(textFile),
                audios =
                    listOf(
                        ChatAudioData(base64 = "YXVkaW8=", mimeType = "audio/wav", name = "voice.wav", sizeBytes = 5),
                    ),
                resourceLinks = listOf(link),
            )

        assertEquals(ContentBlock.Text("prompt"), blocks[0])
        assertEquals(ContentBlock.Image(data = "aW1n", mimeType = "image/png"), blocks[1])
        assertEquals(ContentBlock.Audio(data = "YXVkaW8=", mimeType = "audio/wav"), blocks[2])
        assertEquals(
            ContentBlock.Resource(
                EmbeddedResourceResource.TextResourceContents(
                    text = "hello",
                    uri = "file:///notes.txt",
                    mimeType = "text/plain",
                ),
            ),
            blocks[3],
        )
        assertEquals(
            ContentBlock.ResourceLink(
                name = "main.kt",
                uri = "file:///workspace/main.kt",
                mimeType = "text/x-kotlin",
            ),
            blocks[4],
        )
    }

    @Test
    fun `undecodable text stays a lossless embedded blob`() {
        val file = ChatFileData("notes.txt", "/w==", "text/plain", 1)
        val block = userContentBlocks("", emptyList(), listOf(file)).single()
        assertEquals(
            ContentBlock.Resource(
                EmbeddedResourceResource.BlobResourceContents("/w==", "file:///notes.txt", "text/plain"),
            ),
            block,
        )
    }

    @Test
    fun `text with declared charset is decoded correctly`() {
        val bytes = "café".toByteArray(Charsets.ISO_8859_1)
        val file =
            ChatFileData("notes.txt", Base64.getEncoder().encodeToString(bytes), "text/plain; charset=ISO-8859-1", 4)
        val resource = (userContentBlocks("", emptyList(), listOf(file)).single() as ContentBlock.Resource).resource
        assertEquals("café", (resource as EmbeddedResourceResource.TextResourceContents).text)
        val replayed = requireNotNull(ContentBlock.Resource(resource).chatFile())
        val replayResource =
            (
                userContentBlocks(
                    "",
                    emptyList(),
                    listOf(replayed),
                ).single() as ContentBlock.Resource
            ).resource
        assertEquals("café", (replayResource as EmbeddedResourceResource.TextResourceContents).text)
    }

    @Test
    fun `binary file stays an embedded blob`() {
        val pdf = ChatFileData("report.pdf", "UERG", "application/pdf", 3)

        val block = userContentBlocks("", emptyList(), listOf(pdf)).single()

        assertEquals(
            ContentBlock.Resource(
                EmbeddedResourceResource.BlobResourceContents(
                    blob = "UERG",
                    uri = "file:///report.pdf",
                    mimeType = "application/pdf",
                ),
            ),
            block,
        )
    }

    @Test
    fun `resource links need no prompt capability and round trip through the prompt view`() {
        val link = ChatPromptResourceLink(name = "spec.md", uri = "https://example.com/spec.md")

        val blocks = userContentBlocks("", emptyList(), emptyList(), resourceLinks = listOf(link))

        assertEquals(link, blocks.single().chatPromptResourceLink())
    }

    @Test
    fun `text resources round trip into a file attachment for queue retry`() {
        val block =
            ContentBlock.Resource(
                EmbeddedResourceResource.TextResourceContents(
                    text = "line one",
                    uri = "file:///notes.txt",
                    mimeType = "text/plain",
                ),
            )

        val file = requireNotNull(block.chatFile())

        assertEquals("notes.txt", file.name)
        assertEquals("text/plain", file.mimeType)
        assertEquals("line one", String(Base64.getDecoder().decode(file.base64), StandardCharsets.UTF_8))
    }
}
