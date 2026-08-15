package com.tamimarafat.ferngeist.service

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Thin wrapper around Android foreground service lifecycle calls.
 * Used by [FerngeistApplication] to request the service start, and as a
 * backstop stop mechanism if the service fails to stop itself.
 */
object ForegroundServiceController {
    private const val TAG = "FgServiceController"

    /**
     * Requests the foreground service to start via [Context.startForegroundService].
     * The service's [FerngeistForegroundService.onStartCommand] will call
     * [android.app.Service.startForeground] within 5 seconds to satisfy
     * Android's foreground service deadline.
     */
    fun start(context: Context) {
        val intent =
            Intent(context, FerngeistForegroundService::class.java).apply {
                action = FerngeistForegroundService.ACTION_START
            }
        try {
            context.startForegroundService(intent)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start foreground service", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to start foreground service", e)
        }
    }

    /**
     * Sends an [ACTION_STOP][FerngeistForegroundService.ACTION_STOP] intent to
     * the service via [Context.startService]. If the service is already
     * destroyed this will briefly re-create it only for it to self-stop.
     *
     * Currently used only as a backstop from [FerngeistApplication] — the
     * service normally stops itself via [android.app.Service.stopSelf] on
     * terminal connection states.
     */
    fun stop(context: Context) {
        val intent =
            Intent(context, FerngeistForegroundService::class.java).apply {
                action = FerngeistForegroundService.ACTION_STOP
            }
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to stop foreground service", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to stop foreground service", e)
        }
    }
}
