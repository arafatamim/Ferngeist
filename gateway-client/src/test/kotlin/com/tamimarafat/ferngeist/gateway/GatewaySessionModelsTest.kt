package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewaySessionModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `connectResponse deserializes the v2 handoff`() {
        val raw =
            """
            {
                "runtimeId": "rt-1",
                "sessionId": "sess-42",
                "attachToken": "at-def",
                "websocketUrl": "ws://192.168.1.5:5788/v1/runtimes/rt-1/ws"
            }
            """.trimIndent()
        val resp = json.decodeFromString<GatewayConnectResponse>(raw)
        assertEquals("rt-1", resp.runtimeId)
        assertEquals("sess-42", resp.sessionId)
        assertEquals("at-def", resp.attachToken)
        assertEquals("ws://192.168.1.5:5788/v1/runtimes/rt-1/ws", resp.webSocketUrl)
    }
}
