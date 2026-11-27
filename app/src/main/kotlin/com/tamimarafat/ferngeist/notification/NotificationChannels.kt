package com.tamimarafat.ferngeist.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tamimarafat.ferngeist.R

/**
 * Heads-up channel for cards that need attention (permission requests, errors, crashes).
 * Keeps the historical id so any already-created channel stays valid.
 */
internal const val ALERTS_CHANNEL_ID = "ferngeist_push"

/**
 * Quiet channel for routine updates (completed turns). Separate channel — not just a lower
 * per-notification priority — because on Android O+ the channel's importance is what decides
 * heads-up/sound, and importance is fixed once a channel is created.
 */
internal const val UPDATES_CHANNEL_ID = "ferngeist_push_updates"

/**
 * Creates both card channels if they don't already exist. Safe to call repeatedly. Created
 * eagerly at app start before either notification service can post.
 */
internal fun ensureNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            ALERTS_CHANNEL_ID,
            context.getString(R.string.push_channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.push_channel_alerts_description)
        },
    )
    manager.createNotificationChannel(
        NotificationChannel(
            UPDATES_CHANNEL_ID,
            context.getString(R.string.push_channel_updates_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.push_channel_updates_description)
        },
    )
}
