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

/** Push token registration operations. */
interface GatewayPushRepository {
    /**
     * Registers (or refreshes) this device's FCM push token with the gateway so it
     * can deliver background notifications. The gateway identifies the device from
     * the proof-authed [gatewayCredential]; the body carries only the token + platform.
     */
    suspend fun registerPushToken(
        scheme: String,
        host: String,
        gatewayCredential: String,
        token: String,
        platform: String = "android",
    )
}

/** Workspace file and git operations. */
interface GatewayWorkspaceRepository {
    /**
     * Reads a file inside a runtime's project directory. [path] is relative to the
     * agent's cwd (captured from the ACP session/new params.cwd) and must not escape it.
     * The response is the ACP SDK's resource-contents shape (text or binary).
     */
    suspend fun fetchWorkspaceFile(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String,
    ): GatewayFileRead

    /** Returns the git branch, ahead/behind, and changed files for a runtime's project directory. */
    suspend fun fetchGitStatus(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
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
    ): List<ToolCallContent.Diff>
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
    GatewayWorkspaceRepository
