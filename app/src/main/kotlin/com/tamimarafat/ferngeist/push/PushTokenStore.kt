package com.tamimarafat.ferngeist.push

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pushTokenDataStore by preferencesDataStore(name = "ferngeist_push_token")

/**
 * Persists the latest FCM registration token so it survives process death and can
 * be re-registered with gateways without waiting for the next token refresh.
 */
@Singleton
class PushTokenStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val dataStore = context.pushTokenDataStore

        /** The last token issued by FCM, or `null` if none has been observed yet. */
        val token: Flow<String?> = dataStore.data.map { prefs -> prefs[KEY_TOKEN] }

        suspend fun setToken(token: String) {
            dataStore.edit { prefs -> prefs[KEY_TOKEN] = token }
        }

        private companion object {
            val KEY_TOKEN = stringPreferencesKey("fcm_token")
        }
    }
