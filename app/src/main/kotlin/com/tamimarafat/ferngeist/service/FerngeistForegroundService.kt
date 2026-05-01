package com.tamimarafat.ferngeist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tamimarafat.ferngeist.MainActivity
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FerngeistForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "ferngeist_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.tamimarafat.ferngeist.ACTION_START_FOREGROUND"
        const val ACTION_STOP = "com.tamimarafat.ferngeist.ACTION_STOP_FOREGROUND"
        const val ACTION_DISCONNECT = "com.tamimarafat.ferngeist.ACTION_DISCONNECT"
    }

    @Inject
    lateinit var connectionManager: AcpConnectionManager

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

        if (!isStarted) {
            isStarted = true
            val notification =
                buildNotification(connectionManager.connectionState.value, connectionManager.agentInfo.value?.name)
            startForeground(NOTIFICATION_ID, notification)
            observeConnectionState()
        }

        return START_STICKY
    }

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
                    connectionManager.connectionState
                        .collect { state ->
                            if (!isStarted) return@collect
                            updateNotification(state)
                            if (state is AcpConnectionState.Disconnected) {
                                stopSelf()
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
            }
    }

    private fun updateNotification(state: AcpConnectionState) {
        if (!isStarted) return
        val notification = buildNotification(state, connectionManager.agentInfo.value?.name)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
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
                        getString(R.string.notification_connected_text, displayName ?: "agent")
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

        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )

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
