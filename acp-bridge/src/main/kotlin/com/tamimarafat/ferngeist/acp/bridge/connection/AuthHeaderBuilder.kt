package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.gateway.GatewayConnectResponse
import com.tamimarafat.ferngeist.gateway.resolveGatewayWebSocketUrl

/**
 * The transport config for one ACP connection, in exactly one of two shapes: a
 * directly-reachable [Manual] server, or a gateway-provisioned runtime [Gateway].
 * Gateway-ness used to be inferred from a null field, so every reader re-derived
 * it; the type states it once.
 */
sealed interface AcpConnectionConfig {
    /** Scheme and host of the gateway REST base (and of the endpoint itself, for [Manual]). */
    val scheme: String
    val host: String

    /** Name to show for the target, or null when the caller has none to publish. */
    val displayName: String?

    /** Auth method to re-run after a reconnect; null leaves the agent's own default. */
    val preferredAuthMethodId: String?

    /** The WebSocket endpoint to dial. */
    val endpoint: String

    /**
     * A directly-reachable ACP server. Never a resilient session: there is no
     * gateway runtime behind it to resume.
     */
    data class Manual(
        override val scheme: String,
        override val host: String,
        override val preferredAuthMethodId: String?,
        override val displayName: String?,
    ) : AcpConnectionConfig {
        override val endpoint: String get() = "$scheme://$host"
    }

    /**
     * A gateway runtime handoff: the runtime's WebSocket endpoint plus the session
     * the gateway provisioned for it. Every v2 handoff carries a session, so [resume]
     * is always present; the attach URL is built from it.
     */
    data class Gateway(
        override val endpoint: String,
        val runtimeId: String,
        val sourceId: String,
        val gatewayCredential: String,
        val resume: ResumeInfo,
        override val scheme: String,
        override val host: String,
        override val preferredAuthMethodId: String?,
        override val displayName: String?,
    ) : AcpConnectionConfig

    /**
     * The gateway session a connection attaches to: the id the gateway minted plus
     * the token that authorises the attach.
     */
    data class ResumeInfo(
        val sessionId: String,
        val attachToken: String,
    )

    companion object {
        /**
         * The transport config for a gateway runtime handoff: the runtime's
         * WebSocket endpoint plus the session the gateway provisioned for it.
         * This is the only place a connect handoff becomes a gateway connection.
         *
         * [displayName] is the name to show for the target, or null when the
         * caller has none to publish.
         */
        fun fromGatewayHandoff(
            gatewaySource: GatewaySource,
            handoff: GatewayConnectResponse,
            authMethodId: String?,
            displayName: String?,
        ): Gateway =
            Gateway(
                endpoint = resolveGatewayWebSocketUrl(gatewaySource, handoff),
                runtimeId = handoff.runtimeId,
                sourceId = gatewaySource.id,
                gatewayCredential = gatewaySource.gatewayCredential,
                resume = ResumeInfo(sessionId = handoff.sessionId, attachToken = handoff.attachToken),
                scheme = gatewaySource.scheme,
                host = gatewaySource.host,
                preferredAuthMethodId = authMethodId,
                displayName = displayName,
            )
    }
}

/** The transport config for a directly-reachable manual ACP server. */
fun LaunchableTarget.Manual.toConnectionConfig(): AcpConnectionConfig =
    AcpConnectionConfig.Manual(
        scheme = server.scheme,
        host = server.host,
        preferredAuthMethodId = server.preferredAuthMethodId,
        displayName = name,
    )

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
    LOAD("session/load", "Failed to load session"),
    RESUME("session/resume", "Failed to resume session"),
}

/** Single capability→RPC decision; see [SessionAttachRpc]. */
fun AgentCapabilities?.sessionAttachRpc(): SessionAttachRpc? {
    if (this == null) return SessionAttachRpc.LOAD
    return when {
        loadSession -> SessionAttachRpc.LOAD
        supportsResume() -> SessionAttachRpc.RESUME
        else -> null
    }
}

@OptIn(UnstableApi::class)
fun AgentCapabilities.displayLabels(): List<String> =
    listOf(
        loadSession to "Load",
        promptCapabilities.image to "Images",
        promptCapabilities.embeddedContext to "Context",
        promptCapabilities.audio to "Audio",
        mcpCapabilities.http to "MCP HTTP",
        mcpCapabilities.sse to "MCP SSE",
        (sessionCapabilities.list != null) to "List",
        supportsResume() to "Resume",
        (sessionCapabilities.fork != null) to "Fork",
    ).filter { (advertised, _) -> advertised }
        .map { (_, label) -> label }

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
