package com.tamimarafat.ferngeist.push

import android.util.Log
import com.tamimarafat.ferngeist.gateway.GatewayPushSubscription
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import javax.inject.Inject

/**
 * Receives UnifiedPush events: a gateway's endpoint ([onNewEndpoint]) goes to
 * [PushRegistrar] for upload, and a decrypted push ([onMessage]) becomes a notification via
 * [PushNotifier]. The `instance` of every event is the gateway's local id.
 */
@AndroidEntryPoint
class FerngeistPushService : PushService() {
    @Inject
    lateinit var pushRegistrar: PushRegistrar

    @Inject
    lateinit var pushNotifier: PushNotifier

    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        val keys = endpoint.pubKeySet
        if (keys == null) {
            // Without keys the gateway cannot encrypt to us, and it only sends encrypted pushes.
            Log.w(TAG, "Endpoint for $instance has no Web Push keys; not registering it")
            return
        }
        pushRegistrar.onNewEndpoint(
            instance,
            GatewayPushSubscription(endpoint.url, GatewayPushSubscription.Keys(p256dh = keys.pubKey, auth = keys.auth)),
        )
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        // The gateway always encrypts, so a message the connector couldn't decrypt isn't its.
        if (!message.decrypted) return
        val payload =
            try {
                json.decodeFromString<Map<String, String>>(message.content.decodeToString())
            } catch (e: SerializationException) {
                Log.w(TAG, "Unreadable push payload for $instance", e)
                return
            }
        // Events arrive on the main thread; the lookups behind a notification are database reads.
        scope.launch {
            if (pushRegistrar.acceptsMessageFor(instance)) pushNotifier.show(payload)
        }
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        Log.w(TAG, "Push registration failed for $instance: $reason")
    }

    override fun onUnregistered(instance: String) {
        pushRegistrar.onUnregistered(instance)
    }

    private companion object {
        const val TAG = "FerngeistPushService"
        val json = Json { ignoreUnknownKeys = true }

        // Outlives the briefly bound service so a notification still posts after it unbinds.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
