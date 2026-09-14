package com.tamimarafat.ferngeist.push

import android.content.Context

/**
 * FOSS flavour: no proprietary push transport is linked, so token registration is a no-op.
 * The Google flavour fetches an FCM token and registers it with every paired gateway; this
 * build ships without push notifications (see README).
 */
object FcmTokenBootstrap {
    fun start(
        context: Context,
        registrar: PushTokenRegistrar,
    ) {
        // Intentionally empty: the FOSS build does not bundle Firebase Cloud Messaging.
    }
}
