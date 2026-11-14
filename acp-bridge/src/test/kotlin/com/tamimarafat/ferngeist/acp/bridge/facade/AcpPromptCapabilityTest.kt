@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.PromptCapabilities
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcpPromptCapabilityTest {
    private val image = ChatImageData("aW1n", "image/png")
    private val audio = ChatAudioData(base64 = "YXVkaW8=", mimeType = "audio/wav", name = "voice.wav", sizeBytes = 5)
    private val file = ChatFileData("report.pdf", "UERG", "application/pdf", 3)

    @Test
    fun `audio is rejected when the agent does not advertise it`() {
        val capabilities = capabilities(image = true, audio = false, embeddedContext = true)

        assertEquals(
            "This agent does not advertise audio prompt support.",
            sendCapabilityRejection(capabilities, emptyList(), listOf(audio), emptyList()),
        )
    }

    @Test
    fun `images and audio pass when the agent advertises both`() {
        val capabilities = capabilities(image = true, audio = true, embeddedContext = false)

        assertNull(sendCapabilityRejection(capabilities, listOf(image), listOf(audio), emptyList()))
    }

    @Test
    fun `embedded context is still required for files but not audio-only prompts`() {
        val capabilities = capabilities(image = false, audio = true, embeddedContext = false)

        assertEquals(
            "This agent does not advertise file attachment support.",
            sendCapabilityRejection(capabilities, emptyList(), emptyList(), listOf(file)),
        )
        assertNull(sendCapabilityRejection(capabilities, emptyList(), listOf(audio), emptyList()))
    }

    @Test
    fun `mapping exposes the audio capability to the composer`() {
        val mapped = mapCapabilities(capabilities(image = false, audio = true, embeddedContext = true))

        assertEquals(true, mapped.canSendAudio)
        assertEquals(true, mapped.supportsEmbeddedContext)
    }

    private fun capabilities(
        image: Boolean,
        audio: Boolean,
        embeddedContext: Boolean,
    ) = AgentCapabilities(
        promptCapabilities =
            PromptCapabilities(
                image = image,
                audio = audio,
                embeddedContext = embeddedContext,
            ),
    )
}
