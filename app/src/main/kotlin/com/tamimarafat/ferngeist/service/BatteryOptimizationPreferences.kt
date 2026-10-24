package com.tamimarafat.ferngeist.service

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.batteryOptDataStore by preferencesDataStore(name = "ferngeist_battery_optimization")

/** Built by hand in the battery prompt's composition, so it takes no injection. */
class BatteryOptimizationPreferences(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dataStore = context.batteryOptDataStore
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    val isDismissed: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[keyDismissed] ?: false }

    fun markDismissed() {
        scope.launch {
            dataStore.edit { prefs -> prefs[keyDismissed] = true }
        }
    }

    companion object {
        private val keyDismissed = booleanPreferencesKey("dismissed")
    }
}
