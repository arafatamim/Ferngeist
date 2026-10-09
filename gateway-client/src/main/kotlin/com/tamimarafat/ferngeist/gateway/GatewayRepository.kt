package com.tamimarafat.ferngeist.gateway

import com.agentclientprotocol.model.ToolCallContent

/** Pairing and initial status operations. */
interface GatewayPairingRepository {
    suspend fun fetchStatus(
        scheme: String,
        host: String,
    ): GatewayStatus

    suspend fun startPairing(
        scheme: String,
        host: String,
    ): GatewayPairStartResponse

    suspend fun getPairingStatus(
        scheme: String,
        host: String,
        challengeId: String,
    ): GatewayPairStatusResponse

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
     * Starts a runtime for [agentId]. Pass [new] = true to spawn a distinct
     * runtime rather than reusing an existing one — required to isolate a second
     * concurrent chat, because a runtime leases exactly one session.
     */
    suspend fun startAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
        new: Boolean = false,
    ): GatewayRuntime

    suspend fun connectRuntime(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        sessionMode: String? = null,
    ): GatewayConnectResponse

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
     * Reads a file inside a runtime's project directory. [path] is relative to the
     * agent's cwd (captured from the ACP session/new params.cwd) and must not escape it.
     * The response is the ACP SDK's resource-contents shape (text or binary).
     *
     * [acpSessionId] scopes this and the git calls below to that ACP session's cwd; null
     * falls back to the runtime's most recently opened session. A session the gateway has
     * not seen yet answers 404.
     */
    suspend fun fetchWorkspaceFile(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String,
        acpSessionId: String? = null,
    ): GatewayFileRead

    /** Returns the git branch, ahead/behind, and changed files for a runtime's project directory. */
    suspend fun fetchGitStatus(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        acpSessionId: String? = null,
    ): GatewayGitStatus

    /**
     * Returns the diff entries for a runtime's project directory as the ACP SDK's
     * [com.agentclientprotocol.model.ToolCallContent.Diff] shape. With [path] set the gateway
     * returns a single object (wrapped here as a one-element list); with [path] null it returns
     * the whole-tree array. [oldText] is null for new/untracked files.
     */
    suspend fun fetchGitDiff(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String? = null,
        acpSessionId: String? = null,
    ): List<ToolCallContent.Diff>
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
