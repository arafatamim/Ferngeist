package com.tamimarafat.ferngeist.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tamimarafat.ferngeist.MainActivity
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.core.model.push.FcmPayloadKeys
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.resolveLocalId
import com.tamimarafat.ferngeist.core.model.store.ActiveChatStore
import com.tamimarafat.ferngeist.service.FerngeistForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Receives FCM messages and token refreshes.
 *
 * - [onNewToken] hands the rotated token to [PushTokenRegistrar] for upload to every
 *   paired gateway.
 * - [onMessageReceived] turns a data payload into a notification that deep-links into
 *   the referenced chat session, reusing the same intent extras the foreground-service
 *   notification uses ([FerngeistForegroundService.EXTRA_SERVER_ID] etc.) so taps land
 *   in the right place via [MainActivity].
 *
 * The push's `serverId` is the **gateway-owned** id; it's translated to the local
 * [com.tamimarafat.ferngeist.core.model.GatewaySource.id] (via [GatewaySourceRepository])
 * before deep-linking, because navigation routes on the local id. A redundant push — one
 * for the session the user is already watching in the foreground — is suppressed via
 * [PushNotificationPolicy].
 *
 * Expected data payload keys (all optional): `title`, `body`, `serverId`, `sessionId`,
 * `cwd`, `category`. A push that names `serverId` + `sessionId` deep-links to that chat;
 * otherwise the tap just opens the app.
 */
@AndroidEntryPoint
class FerngeistMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var pushTokenRegistrar: PushTokenRegistrar

    @Inject
    lateinit var gatewaySourceRepository: GatewaySourceRepository

    @Inject
    lateinit var activeChatStore: ActiveChatStore

    @Inject
    lateinit var appForegroundState: AppForegroundState

    override fun onNewToken(token: String) {
        pushTokenRegistrar.onTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val notification = message.notification
        val title = data[FcmPayloadKeys.TITLE] ?: notification?.title ?: getString(R.string.push_default_title)
        val body = data[FcmPayloadKeys.BODY] ?: notification?.body ?: getString(R.string.push_default_body)
        val sessionId = data[FcmPayloadKeys.SESSION_ID]

        // The push carries the gateway-owned id; translate to the local server id that
        // navigation (and ActiveChatStore) use. Null when unknown/legacy → no deep-link.
        // Runs on FCM's background thread, so the brief blocking lookup is acceptable.
        val localServerId =
            data[FcmPayloadKeys.SERVER_ID]?.let { gatewayId ->
                runBlocking { gatewaySourceRepository.resolveLocalId(gatewayId) }
            }

        // Skip pushes the user is already watching live in the foreground. Suppression
        // matches on the stable gatewayId, not the local server id, so it survives the
        // local-id churn (and duplicate records) a re-pair can introduce.
        val isForeground = appForegroundState.isForeground.value
        val activeChat = activeChatStore.activeChat.value
        val activeChatGatewayId = activeChat?.gatewayId
        if (PushNotificationPolicy.shouldSuppress(
                isAppForeground = isForeground,
                activeChat = activeChat,
                activeChatGatewayId = activeChatGatewayId,
                targetGatewayId = data[FcmPayloadKeys.SERVER_ID],
                targetSessionId = sessionId,
            )
        ) {
            return
        }

        ensurePushChannels(this)

        // Route urgent categories to the heads-up Alerts channel, routine ones to the quiet
        // Updates channel. On Android O+ the channel — not per-notification priority —
        // decides whether a push interrupts.
        val built =
            NotificationCompat
                .Builder(this, pushChannelIdFor(data[FcmPayloadKeys.CATEGORY]))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setContentIntent(buildContentIntent(data, localServerId, sessionId))
                .build()

        getSystemService(NotificationManager::class.java)
            .notify(notificationId.incrementAndGet(), built)
    }

    /** Builds the tap target, deep-linking to a chat when the (translated) ids resolve. */
    private fun buildContentIntent(
        data: Map<String, String>,
        localServerId: String?,
        sessionId: String?,
    ): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (localServerId != null && sessionId != null) {
                    putExtra(FerngeistForegroundService.EXTRA_SERVER_ID, localServerId)
                    putExtra(FerngeistForegroundService.EXTRA_SESSION_ID, sessionId)
                    putExtra(FerngeistForegroundService.EXTRA_CWD, data[FcmPayloadKeys.CWD] ?: "/")
                    putExtra(FerngeistForegroundService.EXTRA_GATEWAY_ID, data[FcmPayloadKeys.SERVER_ID])
                }
            }
        return PendingIntent.getActivity(
            this,
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
