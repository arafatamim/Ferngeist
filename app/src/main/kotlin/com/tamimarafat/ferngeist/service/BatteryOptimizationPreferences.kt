package com.tamimarafat.ferngeist.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryOptimizationPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dismissedFlow = MutableStateFlow(sharedPreferences.getBoolean(KEY_DISMISSED, false))

    val isDismissed: StateFlow<Boolean> = dismissedFlow.asStateFlow()

    fun markDismissed() {
        if (dismissedFlow.value) return
        sharedPreferences.edit().putBoolean(KEY_DISMISSED, true).apply()
        dismissedFlow.value = true
    }

    companion object {
        private const val PREFS_NAME = "ferngeist_battery_optimization"
        private const val KEY_DISMISSED = "dismissed"
    }
}