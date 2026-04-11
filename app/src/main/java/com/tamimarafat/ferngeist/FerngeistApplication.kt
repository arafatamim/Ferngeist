package com.tamimarafat.ferngeist

import android.app.Application
import android.util.Log
import com.tamimarafat.ferngeist.service.AppKeepAliveService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FerngeistApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FerngeistCrash", "Uncaught exception on thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }

        AppKeepAliveService.start(this)
    }
}
