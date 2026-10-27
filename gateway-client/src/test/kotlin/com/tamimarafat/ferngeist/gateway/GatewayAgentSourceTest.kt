package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayAgentSourceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `leaves source null on a gateway that predates custom agents`() {
        val response =
            json.decodeFromString<GatewayAgentsResponse>(
                """{"agents":[{"id":"mock-acp","displayName":"Mock","detected":true,""" +
                    """"manifestValid":true,"security":{"allowsRemoteStart":true}}]}""",
            )
        assertNull(response.agents.single().source)
    }

    @Test
    fun `preserves bundled icon from catalog response`() {
        val payload =
            """{"agents":[{"id":"custom-omp","displayName":"OMP","detected":true,""" +
                """"manifestValid":true,"security":{"allowsRemoteStart":true},"icon":"data:image/svg+xml;base64,PHN2Zy8+"}]}"""
        val response = json.decodeFromString<GatewayAgentsResponse>(payload)
        assertEquals("data:image/svg+xml;base64,PHN2Zy8+", response.agents.single().preferredIcon)
    }

    @Test
    fun `registry icon takes precedence over bundled icon`() {
        val payload =
            """{"agents":[{"id":"omp","displayName":"OMP","detected":true,""" +
                """"manifestValid":true,"security":{"allowsRemoteStart":true},""" +
                """"icon":"data:image/svg+xml;base64,PHN2Zy8+",""" +
                """"registry":{"icon":"https://example.com/omp.svg"}}]}"""
        val response = json.decodeFromString<GatewayAgentsResponse>(payload)
        assertEquals("https://example.com/omp.svg", response.agents.single().preferredIcon)
    }
}
