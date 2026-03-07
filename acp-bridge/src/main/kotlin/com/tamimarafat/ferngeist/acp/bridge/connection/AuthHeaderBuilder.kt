package com.tamimarafat.ferngeist.acp.bridge.connection

data class AcpConnectionConfig(
    val scheme: String = "ws",
    val host: String,
    val preferredAuthMethodId: String? = null,
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
    val envVarName: String? = null,
    val link: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)
sealed interface AcpInitializeResult {
    val agentInfo: AgentInfo
    val authMethods: List<AcpAuthMethodInfo>

    data class Ready(
        override val agentInfo: AgentInfo,
        override val authMethods: List<AcpAuthMethodInfo>,
        val authenticatedMethodId: String? = null,
    ) : AcpInitializeResult

    data class AuthenticationRequired(
        override val agentInfo: AgentInfo,
        override val authMethods: List<AcpAuthMethodInfo>,
        val authErrorMessage: String? = null,
    ) : AcpInitializeResult
}

sealed interface AcpAuthenticateResult {
    data object Success : AcpAuthenticateResult
    data class Failure(val message: String) : AcpAuthenticateResult
}
