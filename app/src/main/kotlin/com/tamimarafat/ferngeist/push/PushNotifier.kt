package com.tamimarafat.ferngeist.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tamimarafat.ferngeist.MainActivity
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.push.PushPayloadKeys
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.resolveLocalId
import com.tamimarafat.ferngeist.service.FerngeistForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a decrypted push payload into a notification that deep-links into the referenced
 * chat, reusing the same intent extras the foreground-service notification uses
 * ([FerngeistForegroundService.EXTRA_SERVER_ID] etc.) so taps land in the right place via
 * [MainActivity].
 *
 * The push's `serverId` is the **gateway-owned** id; it's translated to the local
 * [com.tamimarafat.ferngeist.core.model.GatewaySource.id] (via [GatewaySourceRepository])
 * before deep-linking, because navigation routes on the local id. A redundant push — one
 * for the session the user is already watching in the foreground — is suppressed via
 * [PushNotificationPolicy].
 *
 * Payload keys (all optional): see [PushPayloadKeys]. A push that names `serverId` +
 * `sessionId` deep-links to that chat; otherwise the tap just opens the app.
 */
@Singleton
class PushNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val chatConnectionHub: ChatConnectionHub,
        private val appForegroundState: AppForegroundState,
    ) {
        suspend fun show(data: Map<String, String>) {
            val title = data[PushPayloadKeys.TITLE] ?: context.getString(R.string.push_default_title)
            val body = data[PushPayloadKeys.BODY] ?: context.getString(R.string.push_default_body)
            val sessionId = data[PushPayloadKeys.SESSION_ID]
            val category = data[PushPayloadKeys.CATEGORY]

            // Skip pushes the user is already watching live in the foreground. Suppression
            // matches on the gateway-owned id, not a local id, so it survives the local-id churn
            // (and duplicate records) a re-pair can introduce. The presence carries the local
            // GatewaySource id, so it is translated first: compared raw, it never matched the push.
            val onScreen = chatConnectionHub.onScreenChat.value
            val onScreenGatewayId =
                onScreen?.gatewaySourceId?.let { gatewaySourceRepository.getGateway(it)?.gatewayId }
            if (PushNotificationPolicy.shouldSuppress(
                    isAppForeground = appForegroundState.isForeground.value,
                    foregroundChat = onScreen?.copy(gatewaySourceId = onScreenGatewayId),
                    targetGatewayId = data[PushPayloadKeys.SERVER_ID],
                    targetSessionId = sessionId,
                )
            ) {
                return
            }

            // The push carries the gateway-owned id; translate to the local server id that
            // navigation (and the hub's presence entries) use. Null when unknown → no deep-link.
            val localServerId =
                data[PushPayloadKeys.SERVER_ID]?.let { gatewaySourceRepository.resolveLocalId(it) }

            ensurePushChannels(context)

            // Progress pushes are throttled server-side to one per ~15s per session, so they
            // should replace (not stack) the previous "Agent working" notification for that
            // session. Other categories keep stacking via the incrementing id.
            val notifyId = notificationIdFor(category, sessionId, notificationId::incrementAndGet)

            // Route urgent categories to the heads-up Alerts channel, routine ones to the quiet
            // Updates channel. On Android O+ the channel — not per-notification priority —
            // decides whether a push interrupts.
            val built =
                NotificationCompat
                    .Builder(context, pushChannelIdFor(category))
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setAutoCancel(true)
                    .setContentIntent(buildContentIntent(data, localServerId, sessionId))
                    .build()

            context.getSystemService(NotificationManager::class.java).notify(notifyId, built)
        }

        /** Builds the tap target, deep-linking to a chat when the (translated) ids resolve. */
        private fun buildContentIntent(
            data: Map<String, String>,
            localServerId: String?,
            sessionId: String?,
        ): PendingIntent {
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (localServerId != null && sessionId != null) {
                        putExtra(FerngeistForegroundService.EXTRA_SERVER_ID, localServerId)
                        putExtra(FerngeistForegroundService.EXTRA_SESSION_ID, sessionId)
                        putExtra(FerngeistForegroundService.EXTRA_CWD, data[PushPayloadKeys.CWD] ?: "")
                        putExtra(FerngeistForegroundService.EXTRA_GATEWAY_ID, data[PushPayloadKeys.SERVER_ID])
                    }
                }
            return PendingIntent.getActivity(
                context,
                requestCode.incrementAndGet(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private companion object {
            // Distinct, ascending ids so multiple pushes don't overwrite one another,
            // kept clear of the foreground (1) and error (2) notification ids. Unique
            // request codes likewise stop FLAG_UPDATE_CURRENT from clobbering extras.
            val notificationId = AtomicInteger(1000)
            val requestCode = AtomicInteger(2000)
        }
    }
