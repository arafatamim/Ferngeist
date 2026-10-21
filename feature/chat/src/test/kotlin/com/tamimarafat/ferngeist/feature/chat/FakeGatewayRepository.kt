package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayFileContents
import com.tamimarafat.ferngeist.gateway.GatewayFileList
import com.tamimarafat.ferngeist.gateway.GatewayGitCommitFiles
import com.tamimarafat.ferngeist.gateway.GatewayGitLog
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRepository

/**
 * No-op [GatewayRepository] for chat tests that build the real hub: only [closeSession] is
 * implemented (it records the sessions it was asked to delete). Every other method returns
 * [TODO] as it is not exercised — the chat's own git reads go through
 * [com.tamimarafat.ferngeist.core.model.ChatSessionFacade] now.
 */
class FakeGatewayRepository : GatewayRepository {
    /** Gateway sessions a close asked to delete, as (host, sessionId). */
    val closedSessions: MutableList<Pair<String, String>> = mutableListOf()

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
    ) = TODO()

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
