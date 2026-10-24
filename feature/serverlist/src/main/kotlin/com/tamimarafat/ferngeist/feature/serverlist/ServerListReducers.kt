package com.tamimarafat.ferngeist.feature.serverlist

import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLaunchEvent
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLaunchOutcome
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.LaunchableTarget

/** Surfaces the auth prompt an agent raised, with the values it already knows. */
internal fun ServerListUiState.withAuthenticationPrompt(
    server: LaunchableTarget,
    outcome: AgentLaunchOutcome.AuthenticationRequired,
): ServerListUiState =
    copy(
        connectingServerId = null,
        pendingAuthentication =
            PendingAuthentication(
                serverId = server.id,
                serverName = server.name,
                agentName = outcome.agentName,
                authMethods = outcome.methods,
                persistedEnvValues = outcome.persistedEnvValues,
                authErrorMessage = outcome.errorMessage,
                gatewayBacked = outcome.gatewayBacked,
            ),
        connectedServerState =
            connectedServerState?.copy(
                agentName = outcome.agentName,
                capabilities = outcome.capabilities,
                isInitializing = false,
            ),
    )

/** Surfaces a launch that never reached a usable agent. */
internal fun ServerListUiState.withLaunchFailure(outcome: AgentLaunchOutcome.Failed): ServerListUiState =
    copy(
        connectingServerId = null,
        connectionState = ChatConnectionState.Failed(outcome.message),
        connectedServerState = null,
        showConnectionError = outcome.message,
    )

/** Applies an agent event a launch raised on its own: the agent's identity, or a failure. */
internal fun ServerListUiState.withLaunchEvent(event: AgentLaunchEvent): ServerListUiState =
    when (event) {
        is AgentLaunchEvent.Initialized ->
            copy(
                connectedServerState =
                    connectedServerState?.copy(
                        agentName = event.agentName,
                        isInitializing = false,
                    ),
            )

        is AgentLaunchEvent.Failed -> withLaunchError(event.message)
    }

/** A failure during an auth attempt lands on the auth prompt; any other lands as the connection error. */
private fun ServerListUiState.withLaunchError(message: String): ServerListUiState {
    val prompt = pendingAuthentication
    return if (prompt != null && connectingServerId == prompt.serverId) {
        copy(
            connectingServerId = null,
            pendingAuthentication = prompt.copy(authErrorMessage = message),
            connectedServerState = connectedServerState?.copy(isInitializing = false),
        )
    } else {
        copy(
            showConnectionError = message,
            connectedServerState = connectedServerState?.copy(isInitializing = false),
        )
    }
}
