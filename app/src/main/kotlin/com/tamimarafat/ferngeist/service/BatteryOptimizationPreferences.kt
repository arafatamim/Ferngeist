package com.tamimarafat.ferngeist.service

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.batteryOptDataStore by preferencesDataStore(name = "ferngeist_battery_optimization")

@Singleton
class BatteryOptimizationPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val dataStore = context.batteryOptDataStore

        val isDismissed: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[KEY_DISMISSED] ?: false }

        fun markDismissed() {
            scope.launch {
                dataStore.edit { prefs -> prefs[KEY_DISMISSED] = true }
            }
        }

        companion object {
            private val KEY_DISMISSED = booleanPreferencesKey("dismissed")
            private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }
