package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `sessionResumeResponse deserializes`() {
        val raw = """{"attachToken":"at-resume-1"}""".trimIndent()
        val resp = json.decodeFromString<GatewaySessionResumeResponse>(raw)
        assertEquals("at-resume-1", resp.attachToken)
    }

    @Test
    fun `repository interface compiles with session methods`() {
        val impl =
            object : GatewayRepository {
                override suspend fun createWorktree(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    repo: String,
                    base: String?,
                    branch: String?,
                ) = TODO()

                override suspend fun listWorktrees(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                ) = TODO()

                override suspend fun listUnmanagedWorktrees(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    repo: String,
                ) = TODO()

                override suspend fun adoptWorktree(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    path: String,
                    repo: String?,
                ) = TODO()

                override suspend fun fetchRepoRefs(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    repo: String,
                ) = TODO()

                override suspend fun deleteWorktree(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    worktreeId: String,
                    force: Boolean,
                ) = TODO()

                override suspend fun fetchStatus(
                    scheme: String,
                    host: String,
                ) = TODO()

                override suspend fun startPairing(
                    scheme: String,
                    host: String,
                ) = TODO()

                override suspend fun fetchAgents(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                ) = TODO()

                override suspend fun connectAgent(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    agentId: String,
                ) = TODO()

                override suspend fun restartRuntime(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                    envVars: Map<String, String>,
                ) = TODO()

                override suspend fun fetchRuntimeLogs(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                ) = TODO()

                override suspend fun completePairing(
                    scheme: String,
                    host: String,
                    challengeId: String,
                    code: String,
                    deviceName: String,
                ) = TODO()

                override suspend fun refreshCredential(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                ) = TODO()

                override suspend fun resumeSession(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    sessionId: String,
                ): GatewaySessionResumeResponse = TODO()

                override suspend fun listGatewaySessions(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                ): List<GatewaySessionSummary> = TODO()

                override suspend fun closeSession(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    sessionId: String,
                ) = TODO()

                override suspend fun getPushConfig(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                ): GatewayPushConfig = TODO()

                override suspend fun registerPushSubscription(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    subscription: GatewayPushSubscription,
                ) = TODO()

                override suspend fun fetchGitStatus(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                    acpSessionId: String?,
                    compare: String?,
                ): GatewayGitStatus = TODO()

                override suspend fun fetchGitDiff(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                    path: String?,
                    acpSessionId: String?,
                    commit: String?,
                    oldPath: String?,
                    compare: String?,
                ): List<com.agentclientprotocol.model.ToolCallContent.Diff> = TODO()

                override suspend fun fetchGitLog(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                    head: String?,
                    skip: Int,
                    limit: Int,
                    acpSessionId: String?,
                    compare: String?,
                    beyond: Int,
                ): GatewayGitLog = TODO()

                override suspend fun fetchGitRefs(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                    acpSessionId: String?,
                ): GatewayGitRefs = TODO()

                override suspend fun fetchGitCommit(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                    sha: String,
                    acpSessionId: String?,
                ): GatewayGitCommitFiles = TODO()

                override suspend fun fetchFile(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                    path: String,
                    acpSessionId: String?,
                ): GatewayFileContents = TODO()

                override suspend fun fetchFileList(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    runtimeId: String,
                    acpSessionId: String?,
                ): GatewayFileList = TODO()

                override suspend fun createCustomAgent(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    displayName: String,
                    command: String,
                    args: List<String>,
                    hint: String,
                ): GatewayAgent = TODO()

                override suspend fun deleteCustomAgent(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    agentId: String,
                ) = TODO()

                override suspend fun stopAgent(
                    scheme: String,
                    host: String,
                    gatewayCredential: String,
                    agentId: String,
                ) = TODO()
            }
        assertNotNull(impl)
    }

    @Test
    fun `sessionSummary deserializes`() {
        val raw =
            """
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
