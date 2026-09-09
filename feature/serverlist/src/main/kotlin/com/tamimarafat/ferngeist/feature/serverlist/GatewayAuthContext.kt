package com.tamimarafat.ferngeist.feature.serverlist

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal data class GatewayAuthContext(
    val server: LaunchableTarget,
    val gatewaySource: GatewaySource,
)

/**
 * Resolves the server and a valid gateway source for an in-flight
 * authentication, deleting expired credentials. Returns null after
 * surfacing the error that stopped authentication.
 */
internal suspend fun resolveGatewayAuthContext(
    pending: PendingAuthentication,
    uiState: MutableStateFlow<ServerListUiState>,
    launchableTargetRepository: LaunchableTargetRepository,
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
): GatewayAuthContext? {
    val server = resolveAuthTarget(pending, uiState, launchableTargetRepository) ?: return null
    val gatewayTarget =
        server as? LaunchableTarget.GatewayAgent
            ?: return failGatewayAuth(uiState, pending, "Gateway was not found for ${server.name}.")
    val gatewaySource =
        resolveAuthGatewaySource(pending, uiState, gatewayTarget, gatewayRepository, gatewaySourceRepository)
            ?: return null
    return GatewayAuthContext(server, gatewaySource)
}

/** Resolves the pending server, or surfaces the removed-server error. */
internal suspend fun resolveAuthTarget(
    pending: PendingAuthentication,
    uiState: MutableStateFlow<ServerListUiState>,
    launchableTargetRepository: LaunchableTargetRepository,
): LaunchableTarget? =
    withContext(Dispatchers.IO) {
        launchableTargetRepository.getTarget(pending.serverId)
    }
        ?: failGatewayAuth(uiState, pending, "Server was removed before authentication could complete.")

/**
 * Refreshes the gateway source, deleting the stored credential when it has
 * expired. Returns null after surfacing the failure.
 */
internal suspend fun resolveAuthGatewaySource(
    pending: PendingAuthentication,
    uiState: MutableStateFlow<ServerListUiState>,
    gatewayTarget: LaunchableTarget.GatewayAgent,
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
): GatewaySource? {
    val source =
        try {
            withContext(Dispatchers.IO) {
                refreshGatewaySourceIfNeeded(gatewayTarget.gatewaySource, gatewayRepository, gatewaySourceRepository)
            }
        } catch (_: GatewayCredentialExpiredException) {
            // The stored credential is dead — clear it so the user is
            // forced through the pairing flow again.
            withContext(Dispatchers.IO) {
                gatewaySourceRepository.deleteGateway(gatewayTarget.gatewaySource.id)
            }
            return failGatewayAuth(uiState, pending, "Gateway credential expired. Please pair this gateway again.")
        }
    if (source.gatewayCredential.isBlank()) {
        return failGatewayAuth(uiState, pending, "Gateway is not paired.")
    }
    return source
}

/** Surfaces the auth failure and signals the caller to stop. */
internal fun failGatewayAuth(
    uiState: MutableStateFlow<ServerListUiState>,
    pending: PendingAuthentication,
    message: String,
): Nothing? {
    uiState.update {
        it.copy(
            connectingServerId = null,
            pendingAuthentication = pending.copy(authErrorMessage = message),
        )
    }
    return null
}
