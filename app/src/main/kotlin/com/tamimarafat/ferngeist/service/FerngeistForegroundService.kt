package com.tamimarafat.ferngeist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tamimarafat.ferngeist.MainActivity
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
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
 * Lifecycle is driven by [AcpConnectionState]:
 * - [AcpConnectionState.Connecting] / [AcpConnectionState.Connected] → keeps running
 * - [AcpConnectionState.Disconnected] → calls [stopSelf] immediately
 * - [AcpConnectionState.Failed] → posts a persistent error notification via
 *   [postErrorNotification], then calls [stopSelf]
 *
 * On [onDestroy] the service disconnects [AcpConnectionManager] if not already
 * disconnected, ensuring clean teardown even on system-kill.
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

        private const val CONTENT_INTENT_REQUEST_CODE = 1
    }

    @Inject
    lateinit var connectionManager: AcpConnectionManager

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
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                observationJob?.cancel()
                scope.launch {
                    connectionManager.disconnect()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (connectionManager.connectionState.value !is AcpConnectionState.Connected &&
            connectionManager.connectionState.value !is AcpConnectionState.Connecting
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isStarted) {
            isStarted = true
            val notification =
                buildNotification(connectionManager.connectionState.value, connectionManager.agentInfo.value?.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            getSystemService(NotificationManager::class.java)
                .cancel(ERROR_NOTIFICATION_ID)
            observeConnectionState()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observationJob?.cancel()
        scope.cancel()

        if (connectionManager.connectionState.value !is AcpConnectionState.Disconnected) {
            connectionManager.disconnect()
        }

        isStarted = false
        super.onDestroy()
    }

    private fun observeConnectionState() {
        observationJob?.cancel()
        observationJob =
            scope.launch {
                launch {
                    connectionManager.connectionState
                        .collect { state ->
                            if (!isStarted) return@collect
                            updateNotification(state)

                            when (state) {
                                is AcpConnectionState.Disconnected -> stopSelf()
                                is AcpConnectionState.Failed -> {
                                    postErrorNotification(state)
                                    stopSelf()
                                }
                                else -> { /* active states, no stop */ }
                            }
                        }
                }
                launch {
                    connectionManager.agentInfo
                        .collect {
                            if (!isStarted) return@collect
                            updateNotification(connectionManager.connectionState.value)
                        }
                }
                launch {
                    // Keep the notification's deep-link target in sync with the
                    // chat the user is currently viewing.
                    activeChatStore.activeChat
                        .collect {
                            if (!isStarted) return@collect
                            updateNotification(connectionManager.connectionState.value)
                        }
                }
            }
    }

    private fun updateNotification(state: AcpConnectionState) {
        if (!isStarted) return
        val notification = buildNotification(state, connectionManager.agentInfo.value?.name)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Posts a regular (non-foreground) notification with the failure error,
     * then immediately stops the service. The notification persists via
     * [setAutoCancel] so the user can read the error after the service dies.
     *
     * Cleared on next service start via [ERROR_NOTIFICATION_ID] cancel in
     * [onStartCommand].
     */
    private fun postErrorNotification(state: AcpConnectionState.Failed) {
        val displayName = connectionManager.currentConnectionConfig()?.serverDisplayName
            ?: connectionManager.agentInfo.value?.name
        val errorText = state.error.message ?: getString(R.string.notification_failed_text)
        val contentIntent = buildContentIntent()
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_failed_title))
                .setContentText(errorText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ERROR_NOTIFICATION_ID, notification)
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

    private fun buildNotification(
        state: AcpConnectionState,
        agentName: String?,
    ): Notification {
        val displayName = connectionManager.currentConnectionConfig()?.serverDisplayName ?: agentName
        val (title, text) =
            when (state) {
                is AcpConnectionState.Connected ->
                    getString(R.string.notification_connected_title) to
                        getString(R.string.notification_connected_text, displayName ?: getString(R.string.notification_agent_fallback))
                is AcpConnectionState.Connecting ->
                    getString(R.string.notification_connecting_title) to
                        getString(R.string.notification_connecting_text)
                is AcpConnectionState.Failed ->
                    getString(R.string.notification_failed_title) to
                        (state.error.message ?: getString(R.string.notification_failed_text))
                is AcpConnectionState.Disconnected ->
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
