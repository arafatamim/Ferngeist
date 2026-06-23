package com.tamimarafat.ferngeist.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoAttention
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raises a local notification when a connected Paseo daemon emits
 * `attention_required` (agent finished, errored, or needs a permission).
 *
 * This is the Paseo replacement for the gateway's notification behaviour while the
 * app process is alive (foreground or backgrounded). Paseo's open-source daemon has
 * no server-side push, so this cannot wake a killed app — that is a known, accepted
 * limitation of replacing the gateway with a local-network Paseo backend.
 */
@Singleton
class PaseoAttentionNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun notify(attention: PaseoAttention) {
            if (!attention.shouldNotify) return
            runCatching {
                ensureChannel()
                val notification =
                    Notification.Builder(context, CHANNEL_ID)
                        .setContentTitle(attention.title ?: titleFor(attention.reason))
                        .setContentText(attention.body ?: bodyFor(attention.reason))
                        .setSmallIcon(context.applicationInfo.icon)
                        .setAutoCancel(true)
                        .build()
                notificationManager().notify(attention.agentId.hashCode(), notification)
            }
        }

        private fun ensureChannel() {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Agent updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifications from Paseo agents (finished, errors, permission requests)."
                }
            notificationManager().createNotificationChannel(channel)
        }

        private fun notificationManager(): NotificationManager =
            context.getSystemService(NotificationManager::class.java)

        private fun titleFor(reason: String): String =
            when (reason) {
                "permission" -> "Permission requested"
                "error" -> "Agent error"
                else -> "Agent finished"
            }

        private fun bodyFor(reason: String): String =
            when (reason) {
                "permission" -> "An agent is waiting for your approval."
                "error" -> "An agent run ended with an error."
                else -> "An agent finished its turn."
            }

        companion object {
            private const val CHANNEL_ID = "paseo_attention"
        }
    }
