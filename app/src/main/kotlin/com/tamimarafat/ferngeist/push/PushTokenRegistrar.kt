package com.tamimarafat.ferngeist.push

import android.util.Log
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps every paired gateway up to date with this device's FCM push token.
 *
 * Registration is driven by the cross product of [PushTokenStore.token] and the set
 * of paired gateways: whenever the token changes or a new gateway is paired, the
 * (gateway, token) pair is POSTed once via [GatewayRepository.registerPushToken].
 * Failures are logged and retried on the next emission (e.g. next app start) — a
 * gateway that is offline now simply gets the token the next time it appears.
 *
 * The token itself is supplied by Firebase (via [FerngeistMessagingService.onNewToken]
 * and [FcmTokenBootstrap]); this class is deliberately Firebase-agnostic so the
 * gateway-registration logic stays testable without Play Services.
 *
 * [scope] is injected (rather than created internally) so tests can drive it with a
 * deterministic test dispatcher; production wiring supplies an IO-backed scope.
 */
class PushTokenRegistrar(
    private val gatewayRepository: GatewayRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val pushTokenStore: PushTokenStore,
    private val scope: CoroutineScope,
) {
    // "<localId>:<credential>:<token>" triples already registered this process, so we don't
    // re-POST on every gateways-list emission. A fresh credential (e.g. after re-pairing)
    // produces a new key and triggers re-registration.
    private val registered = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var started = false

    /** Starts observing token + gateway changes and registering as they appear. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                pushTokenStore.token,
                gatewaySourceRepository.getGateways(),
            ) { token, gateways -> token to gateways }
                .distinctUntilChanged()
                .collect { (token, gateways) ->
                    if (token.isNullOrBlank()) return@collect
                    gateways.forEach { gateway -> register(gateway, token) }
                }
        }
    }

    /** Persists a freshly issued token; the [start] collector picks it up and registers it. */
    fun onTokenRefreshed(token: String) {
        if (token.isBlank()) return
        scope.launch { pushTokenStore.setToken(token) }
    }

    private suspend fun register(
        gateway: GatewaySource,
        token: String,
    ) {
        val refreshed = refreshGatewaySourceIfNeeded(gateway, gatewayRepository, gatewaySourceRepository)
        val key = "${refreshed.id}:${refreshed.gatewayCredential}:$token"
        if (!registered.add(key)) return
        try {
            gatewayRepository.registerPushToken(
                scheme = refreshed.scheme,
                host = refreshed.host,
                gatewayCredential = refreshed.gatewayCredential,
                token = token,
            )
        } catch (e: Exception) {
            // Drop the key so the next emission retries this gateway.
            registered.remove(key)
            Log.w(TAG, "Failed to register push token with gateway ${refreshed.name}", e)
        }
    }

    private companion object {
        const val TAG = "PushTokenRegistrar"
    }
}
