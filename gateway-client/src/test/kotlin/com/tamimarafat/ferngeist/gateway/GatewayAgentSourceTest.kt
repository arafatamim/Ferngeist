package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayAgentSourceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes a custom source`() {
        val response =
            json.decodeFromString<GatewayAgentsResponse>(
                """{"agents":[{"id":"custom-my-agent","displayName":"My Agent","detected":true,""" +
                    """"manifestValid":true,"security":{"allowsRemoteStart":true},"source":"custom"}]}""",
            )
        assertEquals("custom", response.agents.single().source)
    }

    @Test
    fun `leaves source null on a gateway that predates custom agents`() {
        val response =
            json.decodeFromString<GatewayAgentsResponse>(
                """{"agents":[{"id":"mock-acp","displayName":"Mock","detected":true,""" +
                    """"manifestValid":true,"security":{"allowsRemoteStart":true}}]}""",
            )
        assertNull(response.agents.single().source)
    }
}
