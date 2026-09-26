package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities

data class AcpConnectionConfig(
    val scheme: String = "ws",
    val host: String,
    val webSocketUrl: String? = null,
    val webSocketBearerToken: String? = null,
    val preferredAuthMethodId: String? = null,
    val gatewayRuntimeId: String? = null,
    val gatewaySourceId: String? = null,
    val serverDisplayName: String? = null,
    val sessionId: String? = null,
    val attachToken: String? = null,
    val gatewayScheme: String? = null,
    val gatewayHost: String? = null,
    val gatewayCredential: String? = null,
) {
    val isResilientSession: Boolean get() = sessionId != null

    /**
     * True when this connection targets a gateway-backed agent (as opposed to a
     * directly-reachable Manual server). Gateway agents are always launched in
     * resilient mode, so a gateway connection is expected to carry a [sessionId];
     * its absence means the gateway could not provision a session.
     */
    val isGatewayConnection: Boolean get() = gatewayCredential != null
}

sealed interface AcpConnectionState {
    data object Disconnected : AcpConnectionState

    data object Connecting : AcpConnectionState

    data object Connected : AcpConnectionState

    data class Failed(
        val error: Throwable,
    ) : AcpConnectionState
}

data class AcpAuthMethodInfo(
    val id: String,
    val name: String,
    val description: String?,
    val type: String,
    val envVars: List<AuthEnvVarInfo> = emptyList(),
    val link: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

data class AuthEnvVarInfo(
    val name: String,
    val label: String?,
    val secret: Boolean,
    val optional: Boolean,
)

data class AcpAuthChallenge(
    val agentInfo: AgentInfo,
    val authMethods: List<AcpAuthMethodInfo>,
    val message: String,
)

/**
 * Raised when a session RPC indicates that the current ACP connection needs
 * authentication before session/list, session/new, or session/load can proceed.
 */
class AcpAuthenticationRequiredException(
    val challenge: AcpAuthChallenge,
) : IllegalStateException(challenge.message)

/**
 * Raised when a session RPC is attempted while the ACP transport is not
 * connected (no live SDK client). Callers should surface this as a clear
 * "not connected" error instead of silently retrying or returning null.
 */
class AcpDisconnectedException :
    IllegalStateException(
        "Not connected to the ACP server",
    )

/**
 * Raised when the agent refuses to attach a session because it still holds it —
 * another client is attached, or an earlier attach was never released. Only
 * releasing the session frees it (the session list's Disconnect), so the message
 * carries that instruction instead of the raw agent error.
 */
class SessionAlreadyActiveException(
    message: String,
) : IllegalStateException(message)

/**
 * True when the agent advertises `sessionCapabilities.resume`, i.e. it can
 * reattach to an existing session without replaying the conversation.
 */
@OptIn(UnstableApi::class)
fun AgentCapabilities.supportsResume(): Boolean = sessionCapabilities.resume != null

/**
 * The session-attach RPC an agent's capabilities permit: `session/load` replays
 * the conversation, `session/resume` only reattaches, and null means it
 * advertises neither — an existing session cannot be reopened over ACP.
 *
 * Unobserved capabilities (still null) count as load-capable, matching what the
 * callers did before this predicate existed.
 */
enum class SessionAttachRpc(
    val rpc: String,
    val failureLabel: String,
) {
    Load("session/load", "Failed to load session"),
    Resume("session/resume", "Failed to resume session"),
}

/** Single capability→RPC decision; see [SessionAttachRpc]. */
fun AgentCapabilities?.sessionAttachRpc(): SessionAttachRpc? {
    if (this == null) return SessionAttachRpc.Load
    return when {
        loadSession -> SessionAttachRpc.Load
        supportsResume() -> SessionAttachRpc.Resume
        else -> null
    }
}

@OptIn(UnstableApi::class)
fun AgentCapabilities.displayLabels(): List<String> =
    buildList {
        if (loadSession) add("Load")
        if (promptCapabilities.image) add("Images")
        if (promptCapabilities.embeddedContext) add("Context")
        if (promptCapabilities.audio) add("Audio")
        if (mcpCapabilities.http) add("MCP HTTP")
        if (mcpCapabilities.sse) add("MCP SSE")
        if (sessionCapabilities.list != null) add("List")
        if (supportsResume()) add("Resume")
        if (sessionCapabilities.fork != null) add("Fork")
    }

sealed interface AcpInitializeResult {
    val agentInfo: AgentInfo
    val agentCapabilities: AgentCapabilities
    val authMethods: List<AcpAuthMethodInfo>

    data class Ready(
        override val agentInfo: AgentInfo,
        override val agentCapabilities: AgentCapabilities,
        override val authMethods: List<AcpAuthMethodInfo>,
        val authenticatedMethodId: String? = null,
    ) : AcpInitializeResult

    data class AuthenticationRequired(
        override val agentInfo: AgentInfo,
        override val agentCapabilities: AgentCapabilities,
        override val authMethods: List<AcpAuthMethodInfo>,
        val authErrorMessage: String? = null,
    ) : AcpInitializeResult
}

sealed interface AcpAuthenticateResult {
    data object Success : AcpAuthenticateResult

    data class Failure(
        val message: String,
    ) : AcpAuthenticateResult
}
