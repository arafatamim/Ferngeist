package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import kotlinx.coroutines.CancellationException
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
        try {
            gatewayRepository.refreshCredential(
                scheme = gatewaySource.scheme,
                host = gatewaySource.host,
                gatewayCredential = gatewaySource.gatewayCredential,
            )
        } catch (error: GatewayCredentialExpiredException) {
            // The credential is dead (expired past the gateway's grace window, or
            // legacy bearer credentials disabled). Swallowing this would leave the
            // app stuck with a credential every authed call 401s on; propagate so
            // callers can clear it and prompt re-pairing.
            throw error
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return gatewaySource
        }
    val updated =
        gatewaySource.copy(
            gatewayCredential = refreshed.gatewayCredential,
            gatewayCredentialExpiresAt = refreshed.expiresAt.toEpochMillisOrNull(),
            gatewayId = refreshed.gatewayId ?: gatewaySource.gatewayId,
        )
    gatewaySourceRepository.updateGateway(updated)
    return updated
}

private fun String.toEpochMillisOrNull(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
