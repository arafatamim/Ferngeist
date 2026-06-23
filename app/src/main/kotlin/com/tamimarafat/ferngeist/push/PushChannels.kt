package com.tamimarafat.ferngeist.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tamimarafat.ferngeist.R

// Push categories the gateway may send (mirrors the server-side taxonomy). Urgent ones
// route to the heads-up Alerts channel; everything else stays quiet on Updates.
const val PUSH_CATEGORY_PERMISSION_REQUEST = "permission_request"
const val PUSH_CATEGORY_AGENT_ERROR = "agent_error"
const val PUSH_CATEGORY_AGENT_CRASH = "agent_crash"

/**
 * Heads-up channel for pushes that need attention (permission requests, errors, crashes).
 * Keeps the historical id so the manifest's `default_notification_channel_id` and any
 * already-created channel stay valid.
 */
const val PUSH_CHANNEL_ALERTS_ID = "ferngeist_push"

/**
 * Quiet channel for routine updates (completed turns). Separate channel — not just a lower
 * per-notification priority — because on Android O+ the channel's importance is what decides
 * heads-up/sound, and importance is fixed once a channel is created.
 */
const val PUSH_CHANNEL_UPDATES_ID = "ferngeist_push_updates"

/**
 * Creates both push channels if they don't already exist. Safe to call repeatedly. Created
 * eagerly at app start (so system-tray FCM "notification" messages have a channel) and
 * defensively from [FerngeistMessagingService].
 */
fun ensurePushChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            PUSH_CHANNEL_ALERTS_ID,
            context.getString(R.string.push_channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.push_channel_alerts_description)
        },
    )
    manager.createNotificationChannel(
        NotificationChannel(
            PUSH_CHANNEL_UPDATES_ID,
            context.getString(R.string.push_channel_updates_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.push_channel_updates_description)
        },
    )
}

/** Urgent categories interrupt via the Alerts channel; routine ones stay quiet on Updates. */
fun pushChannelIdFor(category: String?): String =
    when (category) {
        PUSH_CATEGORY_PERMISSION_REQUEST,
        PUSH_CATEGORY_AGENT_ERROR,
        PUSH_CATEGORY_AGENT_CRASH,
        -> PUSH_CHANNEL_ALERTS_ID
        else -> PUSH_CHANNEL_UPDATES_ID
    }
