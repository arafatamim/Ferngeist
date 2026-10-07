package com.tamimarafat.ferngeist.push

import android.util.Log
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayPushSubscription
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps every paired gateway subscribed to this device's Web Push notifications.
 *
 * Each gateway signs its pushes with its own VAPID key, so each gets its own UnifiedPush
 * registration, keyed by the gateway's local id as the UnifiedPush `instance`:
 *
 * 1. Once a distributor is chosen, every paired gateway's key is fetched
 *    ([GatewayRepository.getPushConfig]) and registered with UnifiedPush.
 * 2. The distributor answers with an endpoint ([onNewEndpoint], on every registration),
 *    which is POSTed to that gateway ([GatewayRepository.registerPushSubscription]).
 * 3. A gateway that disappears from the list is unregistered.
 *
 * Failures are logged and retried on the next emission (e.g. next app start) — a gateway
 * that is offline now is simply subscribed the next time it appears.
 *
 * [scope] is injected (rather than created internally) so tests can drive it with a
 * deterministic test dispatcher; production wiring supplies an IO-backed scope.
 */
class PushRegistrar(
    private val gatewayRepository: GatewayRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val unifiedPush: UnifiedPushClient,
    private val scope: CoroutineScope,
) {
    // "<localId>:<credential>" registered with UnifiedPush this process, and
    // "<localId>:<credential>:<endpoint>" uploaded to the gateway, so neither repeats on
    // every gateways-list emission. A fresh credential (e.g. after re-pairing) produces a
    // new key and triggers both again.
    private val subscribed = ConcurrentHashMap.newKeySet<String>()
    private val uploaded = ConcurrentHashMap.newKeySet<String>()

    // Latest endpoint per instance (gateway local id), as UnifiedPush reported it.
    private val endpoints = MutableStateFlow<Map<String, GatewayPushSubscription>>(emptyMap())
    private val distributorReady = MutableStateFlow(false)

    @Volatile
    private var started = false

    /** Starts observing gateways and endpoints and registering as they appear. Idempotent. */
    fun start() {
        if (started) return
        started = true
        if (unifiedPush.hasDistributor()) distributorReady.value = true
        val gateways = gatewaySourceRepository.getGateways()
        scope.launch {
            var previousIds = emptySet<String>()
            combine(gateways, distributorReady) { list, ready -> list to ready }
                .distinctUntilChanged()
                .collect { (list, ready) ->
                    val ids = list.map { it.id }.toSet()
                    (previousIds - ids).forEach(::forget)
                    previousIds = ids
                    if (ready) list.forEach { subscribe(it) }
                }
        }
        scope.launch {
            combine(gateways, endpoints) { list, byInstance -> list to byInstance }
                .distinctUntilChanged()
                .collect { (list, byInstance) ->
                    list.forEach { gateway -> byInstance[gateway.id]?.let { upload(gateway, it) } }
                }
        }
    }

    /** A distributor was chosen (or confirmed) for this app; registrations can now reach it. */
    fun onDistributorReady() {
        distributorReady.value = true
    }

    fun onNewEndpoint(
        instance: String,
        subscription: GatewayPushSubscription,
    ) {
        endpoints.update { it + (instance to subscription) }
    }

    /** The distributor dropped [instance]; it is registered again on the next emission. */
    fun onUnregistered(instance: String) {
        endpoints.update { it - instance }
        subscribed.removeIf { it.startsWith("$instance:") }
    }

    /**
     * Whether a message for [instance] belongs to a gateway still paired. One that isn't —
     * removed while the app was not running, so [forget] never saw it go — is unregistered
     * so its pushes stop.
     */
    suspend fun acceptsMessageFor(instance: String): Boolean {
        if (gatewaySourceRepository.getGateways().first().any { it.id == instance }) return true
        forget(instance)
        return false
    }

    private fun forget(instance: String) {
        unifiedPush.unregister(instance)
        onUnregistered(instance)
    }

    private suspend fun subscribe(gateway: GatewaySource) {
        val refreshed = refreshOrDrop(gateway) ?: return
        val key = "${refreshed.id}:${refreshed.gatewayCredential}"
        if (!subscribed.add(key)) return
        val ok =
            reportFailures(refreshed, "fetch push config from") {
                val config =
                    gatewayRepository.getPushConfig(refreshed.scheme, refreshed.host, refreshed.gatewayCredential)
                unifiedPush.register(refreshed.id, config.vapidPublicKey)
            }
        if (!ok) subscribed.remove(key)
    }

    private suspend fun upload(
        gateway: GatewaySource,
        subscription: GatewayPushSubscription,
    ) {
        val refreshed = refreshOrDrop(gateway) ?: return
        val key = "${refreshed.id}:${refreshed.gatewayCredential}:${subscription.endpoint}"
        if (!uploaded.add(key)) return
        val ok =
            reportFailures(refreshed, "register push subscription with") {
                gatewayRepository.registerPushSubscription(
                    scheme = refreshed.scheme,
                    host = refreshed.host,
                    gatewayCredential = refreshed.gatewayCredential,
                    subscription = subscription,
                )
            }
        if (!ok) uploaded.remove(key)
    }

    private suspend fun refreshOrDrop(gateway: GatewaySource): GatewaySource? =
        try {
            refreshGatewaySourceIfNeeded(gateway, gatewayRepository, gatewaySourceRepository)
        } catch (error: GatewayCredentialExpiredException) {
            gatewaySourceRepository.deleteGateway(gateway.id)
            Log.w(TAG, "Gateway credential expired, removed gateway ${gateway.name}", error)
            null
        }

    /** Runs [call] against [gateway]; false when it failed in a way worth retrying later. */
    private suspend fun reportFailures(
        gateway: GatewaySource,
        action: String,
        call: suspend () -> Unit,
    ): Boolean =
        try {
            call()
            true
        } catch (error: GatewayCredentialExpiredException) {
            // The credential is dead and cannot be refreshed. Clear it so the
            // gateway disappears from the list (the user can re-pair); retrying
            // would just keep 401ing.
            gatewaySourceRepository.deleteGateway(gateway.id)
            Log.w(TAG, "Gateway credential expired, removed gateway ${gateway.name}", error)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayRequestException) {
            // HTTP error from the gateway, e.g. one too old to serve Web Push.
            Log.w(TAG, "Failed to $action gateway ${gateway.name}", e)
            false
        } catch (e: IOException) {
            Log.w(TAG, "Network error trying to $action gateway ${gateway.name}", e)
            false
        }

    private companion object {
        const val TAG = "PushRegistrar"
    }
}
