package com.tamimarafat.ferngeist.push

import android.content.Context
import android.util.Log
import org.unifiedpush.android.connector.UnifiedPush

/** Seam over UnifiedPush's static API so [PushRegistrar] is testable off-device. */
interface UnifiedPushClient {
    /** Whether a distributor has been chosen, so [register] will reach one. */
    fun hasDistributor(): Boolean

    /** Asks the distributor for an endpoint for [instance]; it arrives in [FerngeistPushService.onNewEndpoint]. */
    fun register(
        instance: String,
        vapidPublicKey: String,
    )

    fun unregister(instance: String)
}

class AndroidUnifiedPushClient(
    private val context: Context,
) : UnifiedPushClient {
    override fun hasDistributor(): Boolean = UnifiedPush.getSavedDistributor(context) != null

    override fun register(
        instance: String,
        vapidPublicKey: String,
    ) {
        try {
            UnifiedPush.register(context, instance = instance, vapid = vapidPublicKey)
        } catch (e: UnifiedPush.VapidNotValidException) {
            // The gateway served a key UnifiedPush can't use; nothing to retry until it changes.
            Log.w(TAG, "Gateway VAPID key rejected for instance $instance", e)
        }
    }

    override fun unregister(instance: String) = UnifiedPush.unregister(context, instance)

    private companion object {
        const val TAG = "UnifiedPushClient"
    }
}
