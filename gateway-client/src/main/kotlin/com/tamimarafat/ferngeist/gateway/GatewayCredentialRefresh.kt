package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import java.time.Instant

private const val GATEWAY_REFRESH_WINDOW_MS = 24L * 60L * 60L * 1000L

suspend fun refreshGatewaySourceIfNeeded(
    gatewaySource: GatewaySource,
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
    nowMillis: Long = System.currentTimeMillis(),
): GatewaySource {
    val expiresAt = gatewaySource.gatewayCredentialExpiresAt ?: return gatewaySource
    if (expiresAt - nowMillis > GATEWAY_REFRESH_WINDOW_MS) {
        return gatewaySource
    }
    val refreshed =
        gatewayRepository.refreshCredential(
            scheme = gatewaySource.scheme,
            host = gatewaySource.host,
            gatewayCredential = gatewaySource.gatewayCredential,
        )
    val updated =
        gatewaySource.copy(
            gatewayCredential = refreshed.gatewayCredential,
            gatewayCredentialExpiresAt = refreshed.expiresAt.toEpochMillisOrNull(),
        )
    gatewaySourceRepository.updateGateway(updated)
    return updated
}

private fun String.toEpochMillisOrNull(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
