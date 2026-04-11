package com.tamimarafat.ferngeist

import android.app.Application
import android.util.Log
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.service.ForegroundServiceController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FerngeistApplication : Application() {

    @Inject
    lateinit var connectionManager: AcpConnectionManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isServiceRunning = false

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FerngeistCrash", "Uncaught exception on thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }

        appScope.launch {
            connectionManager.connectionState
                .collect { state ->
                    when (state) {
                        is AcpConnectionState.Connected,
                        is AcpConnectionState.Connecting,
                        is AcpConnectionState.Failed -> {
                            if (!isServiceRunning) {
                                isServiceRunning = true
                                ForegroundServiceController.start(this@FerngeistApplication)
                            }
                        }
                        is AcpConnectionState.Disconnected -> {
                            if (isServiceRunning) {
                                isServiceRunning = false
                                ForegroundServiceController.stop(this@FerngeistApplication)
                            }
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