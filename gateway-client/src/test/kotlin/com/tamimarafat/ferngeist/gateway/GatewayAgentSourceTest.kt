package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.json.Json
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
}
