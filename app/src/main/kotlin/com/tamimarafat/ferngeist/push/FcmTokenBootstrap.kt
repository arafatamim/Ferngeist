package com.tamimarafat.ferngeist.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Starts push-token registration at app launch.
 *
 * Always starts the [PushTokenRegistrar] collector, then — only when Firebase is
 * actually configured (a real `google-services.json` is present) — fetches the
 * current FCM token and forwards it. When Firebase is absent this is a quiet
 * no-op, keeping push entirely optional (see docs/fcm-setup.md).
 */
object FcmTokenBootstrap {
    private const val TAG = "FcmTokenBootstrap"

    fun start(
        context: Context,
        registrar: PushTokenRegistrar,
    ) {
        registrar.start()
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.i(TAG, "Firebase not configured (no google-services.json); push notifications disabled")
            return
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                task.result?.let(registrar::onTokenRefreshed)
            } else {
                Log.w(TAG, "Failed to fetch FCM token", task.exception)
            }
        }
    }
}
