package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayCreateWorktreeRequest
import com.tamimarafat.ferngeist.gateway.GatewayCustomAgentRequest
import com.tamimarafat.ferngeist.gateway.GatewayFileContents
import com.tamimarafat.ferngeist.gateway.GatewayFileList
import com.tamimarafat.ferngeist.gateway.GatewayGitCommitFiles
import com.tamimarafat.ferngeist.gateway.GatewayGitLog
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import com.tamimarafat.ferngeist.gateway.workspace.GatewayDiffQuery
import com.tamimarafat.ferngeist.gateway.workspace.GatewayLogQuery

/**
 * No-op [GatewayRepository] for chat tests that build the real hub: only [closeSession] is
 * implemented (it records the sessions it was asked to delete), and [listWorktrees] answers an
 * empty gateway listing the switcher can fold paths against. Every other method returns [TODO]
 * as it is not exercised — the chat's own git reads go through
 * [com.tamimarafat.ferngeist.core.model.ChatSessionFacade] now.
 */
class FakeGatewayRepository : GatewayRepository {
    /** Gateway sessions a close asked to delete, as (host, sessionId). */
    val closedSessions: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun createWorktree(
        scheme: String,
        host: String,
        gatewayCredential: String,
        request: GatewayCreateWorktreeRequest,
    ) = TODO()

    override suspend fun listWorktrees(
        scheme: String,
        host: String,
        gatewayCredential: String,
        repo: String?,
    ): List<GatewayWorktree> = emptyList()

    override suspend fun findMissingPaths(
        scheme: String,
        host: String,
        gatewayCredential: String,
        paths: List<String>,
    ): List<String> = TODO()

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
    ) {
        closedSessions.add(host to sessionId)
    }

    override suspend fun getPushConfig(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): com.tamimarafat.ferngeist.gateway.GatewayPushConfig = TODO()

    override suspend fun registerPushSubscription(
        scheme: String,
        host: String,
        gatewayCredential: String,
        subscription: com.tamimarafat.ferngeist.gateway.GatewayPushSubscription,
    ) = Unit

    override suspend fun fetchGitStatus(
        connection: GatewayWorkspaceConnection,
        acpSessionId: String?,
        compare: String?,
    ): GatewayGitStatus = TODO()

    override suspend fun fetchGitDiff(
        connection: GatewayWorkspaceConnection,
        query: GatewayDiffQuery,
    ) = TODO()

    override suspend fun fetchGitLog(
        connection: GatewayWorkspaceConnection,
        query: GatewayLogQuery,
    ): GatewayGitLog = TODO()

    override suspend fun fetchGitRefs(
        connection: GatewayWorkspaceConnection,
        acpSessionId: String?,
    ): GatewayGitRefs = TODO()

    override suspend fun fetchGitCommit(
        connection: GatewayWorkspaceConnection,
        sha: String,
        acpSessionId: String?,
    ): GatewayGitCommitFiles = TODO()

    override suspend fun fetchFile(
        connection: GatewayWorkspaceConnection,
        path: String,
        acpSessionId: String?,
    ): GatewayFileContents = TODO()

    override suspend fun fetchFileList(
        connection: GatewayWorkspaceConnection,
        acpSessionId: String?,
    ): GatewayFileList = TODO()

    override suspend fun createCustomAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        request: GatewayCustomAgentRequest,
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
