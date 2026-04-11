package com.tamimarafat.ferngeist.acp.bridge.connection

data class AcpConnectionConfig(
    val scheme: String = "ws",
    val host: String,
    val webSocketUrl: String? = null,
    val webSocketBearerToken: String? = null,
    val preferredAuthMethodId: String? = null,
    val helperRuntimeId: String? = null,
    val helperSourceId: String? = null,
    val serverDisplayName: String? = null,
)

sealed interface AcpConnectionState {
    data object Disconnected : AcpConnectionState
    data object Connecting : AcpConnectionState
    data object Connected : AcpConnectionState
    data class Failed(val error: Throwable) : AcpConnectionState
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

data class AcpAgentCapabilities(
    val loadSession: Boolean = false,
    val prompt: AcpPromptCapabilities = AcpPromptCapabilities(),
    val mcp: AcpMcpCapabilities = AcpMcpCapabilities(),
    val session: AcpSessionCapabilities = AcpSessionCapabilities(),
) {
    fun displayLabels(): List<String> {
        return buildList {
            if (loadSession) add("Load")
            if (prompt.image) add("Images")
            if (prompt.embeddedContext) add("Context")
            if (prompt.audio) add("Audio")
            if (mcp.http) add("MCP HTTP")
            if (mcp.sse) add("MCP SSE")
            if (session.list) add("List")
            if (session.resume) add("Resume")
            if (session.fork) add("Fork")
        }
    }
}

data class AcpPromptCapabilities(
    val audio: Boolean = false,
    val embeddedContext: Boolean = false,
    val image: Boolean = false,
)

data class AcpMcpCapabilities(
    val http: Boolean = false,
    val sse: Boolean = false,
)

data class AcpSessionCapabilities(
    val fork: Boolean = false,
    val list: Boolean = false,
    val resume: Boolean = false,
)

sealed interface AcpInitializeResult {
    val agentInfo: AgentInfo
    val agentCapabilities: AcpAgentCapabilities
    val authMethods: List<AcpAuthMethodInfo>

    data class Ready(
        override val agentInfo: AgentInfo,
        override val agentCapabilities: AcpAgentCapabilities,
        override val authMethods: List<AcpAuthMethodInfo>,
        val authenticatedMethodId: String? = null,
    ) : AcpInitializeResult

    data class AuthenticationRequired(
        override val agentInfo: AgentInfo,
        override val agentCapabilities: AcpAgentCapabilities,
        override val authMethods: List<AcpAuthMethodInfo>,
        val authErrorMessage: String? = null,
    ) : AcpInitializeResult
}

sealed interface AcpAuthenticateResult {
    data object Success : AcpAuthenticateResult
    data class Failure(val message: String) : AcpAuthenticateResult
}
