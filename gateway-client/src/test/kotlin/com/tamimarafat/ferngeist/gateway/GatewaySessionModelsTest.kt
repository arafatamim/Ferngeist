package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewaySessionModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `connectResponse with session fields deserializes correctly`() {
        val raw = """
        {
            "runtimeId": "rt-1",
            "scheme": "ws",
            "host": "192.168.1.5:5788",
            "websocketUrl": "ws://192.168.1.5:5788/v1/acp/rt-1",
            "websocketPath": "/v1/acp/rt-1",
            "bearerToken": "bt-abc",
            "tokenExpiresAt": "2026-05-26T10:00:00Z",
            "sessionId": "sess-42",
            "attachToken": "at-def"
        }
        """.trimIndent()
        val resp = json.decodeFromString<GatewayConnectResponse>(raw)
        assertEquals("sess-42", resp.sessionId)
        assertEquals("at-def", resp.attachToken)
        assertEquals("rt-1", resp.runtimeId)
    }

    @Test
    fun `connectResponse without session fields defaults to null`() {
        val raw = """
        {
            "runtimeId": "rt-1",
            "scheme": "ws",
            "host": "192.168.1.5:5788",
            "websocketUrl": "ws://192.168.1.5:5788/v1/acp/rt-1",
            "websocketPath": "/v1/acp/rt-1",
            "bearerToken": "bt-abc",
            "tokenExpiresAt": "2026-05-26T10:00:00Z"
        }
        """.trimIndent()
        val resp = json.decodeFromString<GatewayConnectResponse>(raw)
        assertNull(resp.sessionId)
        assertNull(resp.attachToken)
    }

    @Test
    fun `connectRequest serializes sessionMode`() {
        val req = GatewayConnectRequest(sessionMode = "resilient")
        val raw = json.encodeToString(GatewayConnectRequest.serializer(), req)
        assertEquals("""{"sessionMode":"resilient"}""", raw.trim())
    }

    @Test
    fun `connectRequest without sessionMode omits field`() {
        val req = GatewayConnectRequest()
        val raw = json.encodeToString(GatewayConnectRequest.serializer(), req)
        assertEquals("""{}""", raw.trim())
    }

    @Test
    fun `sessionResumeResponse deserializes`() {
        val raw = """{"attachToken":"at-resume-1"}""".trimIndent()
        val resp = json.decodeFromString<GatewaySessionResumeResponse>(raw)
        assertEquals("at-resume-1", resp.attachToken)
    }

    @Test
    fun `repository interface compiles with session methods`() {
        val impl = object : GatewayRepository {
            override suspend fun fetchStatus(scheme: String, host: String) = TODO()
            override suspend fun startPairing(scheme: String, host: String) = TODO()
            override suspend fun getPairingStatus(scheme: String, host: String, challengeId: String) = TODO()
            override suspend fun fetchAgents(scheme: String, host: String, gatewayCredential: String) = TODO()
            override suspend fun startAgent(scheme: String, host: String, gatewayCredential: String, agentId: String) = TODO()
            override suspend fun connectRuntime(scheme: String, host: String, gatewayCredential: String, runtimeId: String, sessionMode: String?) = TODO()
            override suspend fun restartRuntime(scheme: String, host: String, gatewayCredential: String, runtimeId: String, envVars: Map<String, String>) = TODO()
            override suspend fun fetchRuntimeLogs(scheme: String, host: String, gatewayCredential: String, runtimeId: String) = TODO()
            override suspend fun completePairing(scheme: String, host: String, challengeId: String, code: String, deviceName: String) = TODO()
            override suspend fun refreshCredential(scheme: String, host: String, gatewayCredential: String) = TODO()
            override suspend fun resumeSession(scheme: String, host: String, gatewayCredential: String, sessionId: String): GatewaySessionResumeResponse = TODO()
            override suspend fun listGatewaySessions(scheme: String, host: String, gatewayCredential: String): List<GatewaySessionSummary> = TODO()
            override suspend fun closeSession(scheme: String, host: String, gatewayCredential: String, sessionId: String) = TODO()
            override suspend fun registerPushToken(scheme: String, host: String, gatewayCredential: String, token: String, platform: String) = TODO()
        }
        assertNotNull(impl)
    }

    @Test
    fun `sessionSummary deserializes`() {
        val raw = """
        {
            "sessions": [
                {
                    "sessionId": "sess-1",
                    "runtimeId": "rt-1",
                    "agentId": "mock-acp",
                    "status": "disconnected",
                    "createdAt": "2026-05-26T09:00:00Z"
                }
            ]
        }
        """.trimIndent()
        val listResp = json.decodeFromString<GatewaySessionListResponse>(raw)
        assertEquals(1, listResp.sessions.size)
        assertEquals("sess-1", listResp.sessions[0].sessionId)
        assertEquals("disconnected", listResp.sessions[0].status)
    }
}
