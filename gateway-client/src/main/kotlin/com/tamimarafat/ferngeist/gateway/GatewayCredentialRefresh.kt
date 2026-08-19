package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant

private const val GATEWAY_REFRESH_WINDOW_MS = 24L * 60L * 60L * 1000L

suspend fun refreshGatewaySourceIfNeeded(
    gatewaySource: GatewaySource,
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
    nowMillis: Long = System.currentTimeMillis(),
): GatewaySource {
    val expiresAt = gatewaySource.gatewayCredentialExpiresAt
    if (expiresAt == null || expiresAt - nowMillis > GATEWAY_REFRESH_WINDOW_MS) {
        return gatewaySource
    }
    val refreshed =
        refreshGatewayCredential(gatewaySource, gatewayRepository)
            ?: return gatewaySource
    val updated =
        gatewaySource.copy(
            gatewayCredential = refreshed.gatewayCredential,
            gatewayCredentialExpiresAt = refreshed.expiresAt.toEpochMillisOrNull(),
            gatewayId = refreshed.gatewayId ?: gatewaySource.gatewayId,
        )
    gatewaySourceRepository.updateGateway(updated)
    return updated
}

/**
 * Attempts to refresh the gateway credential.
 * Returns null on non-fatal failures (gateway rejected the refresh, network error)
 * so the caller can fall back to the existing credential.
 * [GatewayCredentialExpiredException] and [CancellationException] are rethrown —
 * the former requires caller intervention (re-pair), the latter must not be swallowed.
 */
private suspend fun refreshGatewayCredential(
    gatewaySource: GatewaySource,
    gatewayRepository: GatewayRepository,
): GatewayPairingResult? =
    try {
        gatewayRepository.refreshCredential(
            scheme = gatewaySource.scheme,
            host = gatewaySource.host,
            gatewayCredential = gatewaySource.gatewayCredential,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayRequestException) {
        // Non-fatal: the gateway rejected the refresh (HTTP error, transient failure).
        // Fall back to the existing credential rather than blocking the caller.
        println(
            "GatewayCredentialRefresh: refresh rejected for ${gatewaySource.name}, keeping existing credential: ${e.message}",
        )
        null
    } catch (e: IOException) {
        // Non-fatal: network error during credential refresh.
        // Fall back to the existing credential.
        println(
            "GatewayCredentialRefresh: network error refreshing ${gatewaySource.name}, keeping existing credential: ${e.message}",
        )
        null
    }

private fun String.toEpochMillisOrNull(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
