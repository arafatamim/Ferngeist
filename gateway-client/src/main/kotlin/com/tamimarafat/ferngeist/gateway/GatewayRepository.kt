package com.tamimarafat.ferngeist.gateway

import com.agentclientprotocol.model.ToolCallContent

/** Pairing and initial status operations. */
interface GatewayPairingRepository {
    suspend fun fetchStatus(
        scheme: String,
        host: String,
    ): GatewayStatus

    /**
     * Fails unless the gateway speaks a supported protocol. Launch paths call this on every
     * connect; an implementation may remember a pass, where [fetchStatus] always costs a round trip.
     */
    suspend fun verifyProtocol(
        scheme: String,
        host: String,
    ) {
        fetchStatus(scheme, host).requireSupportedProtocol()
    }

    suspend fun startPairing(
        scheme: String,
        host: String,
    ): GatewayPairStartResponse

    suspend fun completePairing(
        scheme: String,
        host: String,
        challengeId: String,
        code: String,
        deviceName: String,
    ): GatewayPairingResult
}

/** Credential / token authentication operations. */
interface GatewayAuthRepository {
    suspend fun refreshCredential(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): GatewayPairingResult
}

/** Agent runtime lifecycle operations. */
interface GatewayRuntimeRepository {
    suspend fun fetchAgents(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): List<GatewayAgent>

    /**
     * Connects [agentId] on the gateway: one call that launches a fresh agent process
     * and returns the session handoff plus the ACP WebSocket URL to attach to.
     */
    suspend fun connectAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
    ): GatewayConnectResponse

    /**
     * Relaunches the agent behind [runtimeId] with [envVars]. The gateway closes the old
     * session only after the new one starts, and the handoff names a **new** runtime.
     */
    suspend fun restartRuntime(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        envVars: Map<String, String>,
    ): GatewayConnectResponse

    suspend fun fetchRuntimeLogs(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
    ): List<GatewayLogEntry>
}

/** Gateway session lifecycle operations. */
interface GatewaySessionRepository {
    suspend fun resumeSession(
        scheme: String,
        host: String,
        gatewayCredential: String,
        sessionId: String,
    ): GatewaySessionResumeResponse

    suspend fun listGatewaySessions(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): List<GatewaySessionSummary>

    suspend fun closeSession(
        scheme: String,
        host: String,
        gatewayCredential: String,
        sessionId: String,
    )
}

/** Web Push registration operations. */
interface GatewayPushRepository {
    /** Fetches the gateway's VAPID public key, which a subscription must be created with. */
    suspend fun getPushConfig(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): GatewayPushConfig

    /**
     * Registers (or refreshes) this device's Web Push subscription with the gateway so it
     * can deliver background notifications. The gateway identifies the device from the
     * proof-authed [gatewayCredential]; the body carries only the subscription.
     */
    suspend fun registerPushSubscription(
        scheme: String,
        host: String,
        gatewayCredential: String,
        subscription: GatewayPushSubscription,
    )
}

/** Workspace file and git operations. */
interface GatewayWorkspaceRepository {
    /**
     * Returns the git branch, ahead/behind, and changed files for a runtime's project directory.
     * With [compare] (a fully-qualified ref) the files are the working tree against that ref's tip,
     * and the response echoes it; a gateway that ignores the parameter omits the echo.
     */
    suspend fun fetchGitStatus(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        acpSessionId: String? = null,
        compare: String? = null,
    ): GatewayGitStatus

    /**
     * Returns the diff entries for a runtime's project directory as the ACP SDK's
     * [com.agentclientprotocol.model.ToolCallContent.Diff] shape. With [path] set the gateway
     * returns a single object (wrapped here as a one-element list); with [path] null it returns
     * the whole-tree array. [oldText] is null for new/untracked files.
     *
     * With [commit] set, [path] is diffed in that commit against its first parent; pass
     * [oldPath] for a rename or the old side is missing. With [compare] the old side is that ref's
     * tip instead of `HEAD`.
     */
    suspend fun fetchGitDiff(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String? = null,
        acpSessionId: String? = null,
        commit: String? = null,
        oldPath: String? = null,
        compare: String? = null,
    ): List<ToolCallContent.Diff>

    /**
     * One page of history reachable from [head] (`HEAD` when null), newest first. Send the
     * returned head back on later pages so new commits do not shift [skip].
     *
     * Throws [GatewayRequestException] with a 404 status and no `{"error"}` envelope on gateways
     * that predate history, and 400 when a pinned [head] no longer names a commit. With [compare]
     * the page is the commits `HEAD` has that the qualified ref does not; [beyond] > 0 also returns that
     * many commits of the ref's own history from its tip in [GatewayGitLog.target], so 1 is just the tip.
     */
    suspend fun fetchGitLog(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        head: String?,
        skip: Int,
        limit: Int,
        acpSessionId: String? = null,
        compare: String? = null,
        beyond: Int = 0,
    ): GatewayGitLog

    /**
     * The refs a compare can target. Throws [GatewayRequestException] with a 404 status and no
     * `{"error"}` envelope on gateways that predate branch comparison.
     */
    suspend fun fetchGitRefs(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        acpSessionId: String? = null,
    ): GatewayGitRefs

    /** The files [sha] changed under the project directory, against its first parent. */
    suspend fun fetchGitCommit(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        sha: String,
        acpSessionId: String? = null,
    ): GatewayGitCommitFiles

    /** `GET /files`: one file of the project directory; [path] is relative to it. */
    suspend fun fetchFile(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String,
        acpSessionId: String? = null,
    ): GatewayFileContents

    /** `GET /files/list`: every file of the project directory git doesn't ignore. */
    suspend fun fetchFileList(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        acpSessionId: String? = null,
    ): GatewayFileList
}

/** Client-registered custom agent operations. */
interface GatewayCustomAgentRepository {
    suspend fun createCustomAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        displayName: String,
        command: String,
        args: List<String>,
        hint: String,
    ): GatewayAgent

    suspend fun deleteCustomAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
    )

    /** Stops every runtime for the agent; required before deleting a used agent. */
    suspend fun stopAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
    )
}

/** Gateway-managed git worktree operations. */
interface GatewayWorktreeRepository {
    /**
     * Creates a worktree for the git repo containing [repo] on a new branch, returning the
     * directory to open the chat in. [base] and [branch] are the gateway's defaults when null.
     */
    suspend fun createWorktree(
        scheme: String,
        host: String,
        gatewayCredential: String,
        repo: String,
        base: String? = null,
        branch: String? = null,
    ): GatewayWorktree

    /**
     * Lists this gateway's managed worktrees, with `ahead`/`dirty` when git could compute them.
     *
     * Throws [GatewayRequestException] with a 404 status on gateways that predate the worktree
     * API; callers use that to hide the worktree UI.
     */
    suspend fun listWorktrees(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): List<GatewayWorktree>

    /**
     * Lists git worktrees of [repo] that the gateway does not manage. Throws
     * [GatewayRequestException] with 404 on a gateway that predates the route and 422 when
     * [repo] is not a git repository.
     */
    suspend fun listUnmanagedWorktrees(
        scheme: String,
        host: String,
        gatewayCredential: String,
        repo: String,
    ): List<GatewayUnmanagedWorktree>

    /**
     * Brings the existing worktree at [path] under gateway management, so it lists with
     * `ahead`/`dirty` and base-aware diffs. 409 when it is already managed.
     */
    suspend fun adoptWorktree(
        scheme: String,
        host: String,
        gatewayCredential: String,
        path: String,
        repo: String? = null,
    ): GatewayWorktree

    /**
     * A repository's refs and default branch before any chat exists in it (`/v1/worktrees/refs`).
     * Throws [GatewayRequestException] with 404 on a gateway that predates the route.
     */
    suspend fun fetchRepoRefs(
        scheme: String,
        host: String,
        gatewayCredential: String,
        repo: String,
    ): GatewayGitRefs

    /**
     * Removes a managed worktree. [force] discards uncommitted changes instead of failing with 409.
     * The branch outlives the worktree unless git considers it merged, so callers must not assume
     * it is gone.
     */
    suspend fun deleteWorktree(
        scheme: String,
        host: String,
        gatewayCredential: String,
        worktreeId: String,
        force: Boolean = false,
    )
}

/**
 * Composite gateway API — extends all sub-interfaces so callers depend on a single
 * type while each concern stays under the function-count threshold.
 */
interface GatewayRepository :
    GatewayPairingRepository,
    GatewayAuthRepository,
    GatewayRuntimeRepository,
    GatewaySessionRepository,
    GatewayPushRepository,
    GatewayWorkspaceRepository,
    GatewayCustomAgentRepository,
    GatewayWorktreeRepository
