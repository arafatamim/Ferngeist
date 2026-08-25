package com.tamimarafat.ferngeist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tamimarafat.ferngeist.MainActivity
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerRegistry
import com.tamimarafat.ferngeist.core.model.store.ActiveChatStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that owns the persistent connection notification.
 *
 * Lifecycle is driven by [AcpManagerRegistry.anyConnected]: while any ACP
 * manager in the process is connected the service keeps running, and when the
 * aggregate goes inactive it self-stops. The service no longer owns a private
 * [com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager]
 * (per-instance managers are owned by their screens); it observes the registry.
 *
 * Notification text is minimal: the aggregate has no single agent name or
 * failure detail, so the notification renders the generic connected/connecting/
 * disconnected titles without inventing new copy. The disconnect action is
 * retained for parity with the previous UI, but with no single manager to
 * target it stops the service rather than disconnecting a transport.
 */
@AndroidEntryPoint
class FerngeistForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "ferngeist_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.tamimarafat.ferngeist.ACTION_START_FOREGROUND"
        const val ACTION_STOP = "com.tamimarafat.ferngeist.ACTION_STOP_FOREGROUND"
        const val ACTION_DISCONNECT = "com.tamimarafat.ferngeist.ACTION_DISCONNECT"
        const val ERROR_NOTIFICATION_ID = 2

        // Extras carried by the notification's content intent so MainActivity can
        // deep-link straight to the active chat session.
        const val EXTRA_SERVER_ID = "com.tamimarafat.ferngeist.extra.SERVER_ID"
        const val EXTRA_SESSION_ID = "com.tamimarafat.ferngeist.extra.SESSION_ID"
        const val EXTRA_CWD = "com.tamimarafat.ferngeist.extra.CWD"
        const val EXTRA_TITLE = "com.tamimarafat.ferngeist.extra.TITLE"
        const val EXTRA_GATEWAY_ID = "com.tamimarafat.ferngeist.extra.GATEWAY_ID"

        private const val CONTENT_INTENT_REQUEST_CODE = 1
    }

    @Inject
    lateinit var acpManagerRegistry: AcpManagerRegistry

    @Inject
    lateinit var activeChatStore: ActiveChatStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observationJob: Job? = null
    private var isStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (handleTerminalAction(intent)) return START_NOT_STICKY

        // startForeground() MUST happen unconditionally within 5 s of
        // startForegroundService(), regardless of connection state.
        ensureForegroundStarted()

        // Now safe to check state — startForeground() already called.
        if (!shouldContinueObserving()) {
            stopSelf()
            return START_NOT_STICKY
        }

        observeConnectionState()
        return START_STICKY
    }

    /**
     * Handles ACTION_DISCONNECT and ACTION_STOP immediately.
     * Returns true if the intent was a terminal action (and should return
     * START_NOT_STICKY), false otherwise.
     */
    private fun handleTerminalAction(intent: Intent?): Boolean {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                observationJob?.cancel()
                stopSelf()
                return true
            }
            ACTION_STOP -> {
                stopSelf()
                return true
            }
        }
        return false
    }

    /**
     * Ensures startForeground() has been called exactly once, building the
     * notification from the current aggregate state.
     * Clears any lingering error notification from a previous run.
     */
    private fun ensureForegroundStarted() {
        if (isStarted) return
        isStarted = true
        val notification = buildNotification(acpManagerRegistry.anyConnected.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        getSystemService(NotificationManager::class.java)
            .cancel(ERROR_NOTIFICATION_ID)
    }

    /**
     * Returns true when any manager is connected, warranting continued
     * observation. Returns false for a fully idle process, signaling the
     * caller to self-stop.
     */
    private fun shouldContinueObserving(): Boolean = acpManagerRegistry.anyConnected.value

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observationJob?.cancel()
        scope.cancel()
        isStarted = false
        super.onDestroy()
    }

    private fun observeConnectionState() {
        observationJob?.cancel()
        observationJob =
            scope.launch {
                launch {
                    acpManagerRegistry.anyConnected
                        .collect { anyConnected ->
                            if (!isStarted) return@collect
                            updateNotification(anyConnected)
                            if (!anyConnected) stopSelf()
                        }
                }
                launch {
                    // Keep the notification's deep-link target in sync with the
                    // chat the user is currently viewing.
                    activeChatStore.activeChat
                        .collect {
                            if (!isStarted) return@collect
                            updateNotification(acpManagerRegistry.anyConnected.value)
                        }
                }
            }
    }

    private fun updateNotification(anyConnected: Boolean) {
        if (!isStarted) return
        val notification = buildNotification(anyConnected)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Builds the notification's tap target. When a chat session is active it
     * carries deep-link extras so [MainActivity] navigates straight to that chat;
     * otherwise it opens the app's default screen. [PendingIntent.FLAG_UPDATE_CURRENT]
     * keeps the extras current as the active chat changes.
     */
    private fun buildContentIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                activeChatStore.activeChat.value?.let { chat ->
                    putExtra(EXTRA_SERVER_ID, chat.serverId)
                    putExtra(EXTRA_SESSION_ID, chat.sessionId)
                    putExtra(EXTRA_CWD, chat.cwd)
                    putExtra(EXTRA_TITLE, chat.title)
                }
            }
        return PendingIntent.getActivity(
            this,
            CONTENT_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildNotification(anyConnected: Boolean): Notification {
        val (title, text) =
            if (anyConnected) {
                getString(R.string.notification_connected_title) to
                    getString(R.string.notification_connected_text, getString(R.string.notification_agent_fallback))
            } else {
                getString(R.string.notification_disconnected_title) to
                    getString(R.string.notification_disconnected_text)
            }

        val contentIntent = buildContentIntent()

        val disconnectIntent =
            PendingIntent.getService(
                this,
                0,
                Intent(this, FerngeistForegroundService::class.java).apply {
                    action = ACTION_DISCONNECT
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_stop_solid,
                getString(R.string.notification_action_disconnect),
                disconnectIntent,
            ).setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
