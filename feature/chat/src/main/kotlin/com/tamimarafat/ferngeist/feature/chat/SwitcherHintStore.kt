package com.tamimarafat.ferngeist.feature.chat

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.switcherHintDataStore by preferencesDataStore(name = "ferngeist_switcher_hint")

/**
 * One-shot "first-run swipe hint already shown" flag for the session-switcher
 * bubble: false until the hint has been seen, permanently true after.
 */
interface SwitcherHintStore {
    /** True once the first-run hint has been seen; false (unseen) by default. */
    val seen: Flow<Boolean>

    /** Consumes the hint so it is never offered again. */
    suspend fun markSeen()
}

@Singleton
class DataStoreSwitcherHintStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : SwitcherHintStore {
        override val seen: Flow<Boolean> =
            context.switcherHintDataStore.data.map { prefs -> prefs[keySeen] ?: false }

        override suspend fun markSeen() {
            withContext(ioDispatcher) {
                context.switcherHintDataStore.edit { prefs -> prefs[keySeen] = true }
            }
        }

        private companion object {
            val keySeen = booleanPreferencesKey("seen")
        }
    }
