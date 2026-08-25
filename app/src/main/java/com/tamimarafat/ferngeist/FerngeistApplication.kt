package com.tamimarafat.ferngeist

import android.app.Application
import android.util.Log
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerRegistry
import com.tamimarafat.ferngeist.push.AppForegroundState
import com.tamimarafat.ferngeist.push.FcmTokenBootstrap
import com.tamimarafat.ferngeist.push.PushTokenRegistrar
import com.tamimarafat.ferngeist.push.ensurePushChannels
import com.tamimarafat.ferngeist.service.ForegroundServiceController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point that wires Hilt and manages foreground service lifecycle.
 *
 * Observes [AcpManagerRegistry.anyConnected] to request the foreground service
 * start when any ACP manager is connected and reset the tracking flag (with a
 * delayed stop backstop) when none remain connected.
 *
 * A delayed [ForegroundServiceController.stop] backstop ensures the service is
 * torn down even if its internal observation coroutine died before self-stopping.
 */
@HiltAndroidApp
class FerngeistApplication : Application() {
    @Inject
    lateinit var acpManagerRegistry: AcpManagerRegistry

    @Inject
    lateinit var pushTokenRegistrar: PushTokenRegistrar

    @Inject
    lateinit var appForegroundState: AppForegroundState

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isServiceRunning = false

    companion object {
        private const val SERVICE_STOP_BACKSTOP_MS = 10_000L
    }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FerngeistCrash", "Uncaught exception on thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }

        // Track app foreground state so the messaging service can suppress pushes for the
        // session the user is already watching. Registered here on the main thread.
        appForegroundState.start()

        // Push notifications: create the channel and start registering this device's
        // FCM token with every paired gateway. No-op when Firebase isn't configured.
        ensurePushChannels(this)
        FcmTokenBootstrap.start(this, pushTokenRegistrar)

        appScope.launch {
            acpManagerRegistry.anyConnected
                .collect { anyConnected ->
                    if (anyConnected) {
                        if (!isServiceRunning) {
                            isServiceRunning = true
                            ForegroundServiceController.start(this@FerngeistApplication)
                        }
                    } else {
                        isServiceRunning = false
                        appScope.launch {
                            delay(SERVICE_STOP_BACKSTOP_MS)
                            ForegroundServiceController.stop(this@FerngeistApplication)
                        }
                    }
                }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
