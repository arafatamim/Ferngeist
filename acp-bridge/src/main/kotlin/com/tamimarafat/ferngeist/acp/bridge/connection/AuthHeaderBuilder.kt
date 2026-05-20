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
        if (sessionCapabilities.resume != null) add("Resume")
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
