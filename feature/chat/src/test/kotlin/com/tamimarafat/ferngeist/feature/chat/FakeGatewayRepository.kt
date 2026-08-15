package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.gateway.GatewayFileRead
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CompletableDeferred

/** No-op [GatewayRepository] for chat view-model tests. Only [fetchGitDiff] is implemented; all
 * other methods return [TODO] as they are not exercised. */
class FakeGatewayRepository : GatewayRepository {
    var fetchGitDiffError: Throwable? = null
    var fetchGitDiffResult: List<ToolCallContent.Diff> = emptyList()

    /** When set, [fetchGitDiff] suspends until the gate completes, so tests can observe the loading state. */
    var fetchGitDiffGate: CompletableDeferred<Unit>? = null

    /** Per-path gates for [fetchGitDiff], keyed by requested path; take precedence over [fetchGitDiffGate]. */
    val fetchGitDiffGates: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()

    /** Per-path results for [fetchGitDiff], keyed by requested path; take precedence over [fetchGitDiffResult]. */
    val fetchGitDiffResults: MutableMap<String, List<ToolCallContent.Diff>> = mutableMapOf()
    val gitDiffRequests: MutableList<Pair<String?, GatewayWorkspaceConnection>> = mutableListOf()

    override suspend fun fetchStatus(
        scheme: String,
        host: String,
    ) = TODO()

    override suspend fun startPairing(
        scheme: String,
        host: String,
    ) = TODO()

    override suspend fun getPairingStatus(
        scheme: String,
        host: String,
        challengeId: String,
    ) = TODO()

    override suspend fun fetchAgents(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ) = TODO()

    override suspend fun startAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
    ) = TODO()

    override suspend fun connectRuntime(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        sessionMode: String?,
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
    ) = TODO()

    override suspend fun listGatewaySessions(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ) = TODO()

    override suspend fun closeSession(
        scheme: String,
        host: String,
        gatewayCredential: String,
        sessionId: String,
    ) = Unit

    override suspend fun registerPushToken(
        scheme: String,
        host: String,
        gatewayCredential: String,
        token: String,
        platform: String,
    ) = Unit

    override suspend fun fetchWorkspaceFile(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String,
    ): GatewayFileRead = TODO()

    override suspend fun fetchGitStatus(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
    ): GatewayGitStatus = TODO()

    override suspend fun fetchGitDiff(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String?,
    ): List<ToolCallContent.Diff> {
        gitDiffRequests.add(path to GatewayWorkspaceConnection(runtimeId, scheme, host, gatewayCredential))
        path?.let { fetchGitDiffGates[it] }?.await()
        fetchGitDiffGate?.await()
        fetchGitDiffError?.let { throw it }
        return path?.let { fetchGitDiffResults[it] } ?: fetchGitDiffResult
    }
}
