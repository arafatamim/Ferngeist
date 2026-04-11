package com.tamimarafat.ferngeist.service

import android.content.Context
import android.content.Intent
import android.util.Log

object ForegroundServiceController {

    private const val TAG = "FgServiceController"

    fun start(context: Context) {
        val intent = Intent(context, FerngeistForegroundService::class.java).apply {
            action = FerngeistForegroundService.ACTION_START
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, FerngeistForegroundService::class.java).apply {
            action = FerngeistForegroundService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }
}