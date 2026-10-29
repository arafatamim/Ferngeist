package com.tamimarafat.ferngeist.feature.serverlist.consent

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.consentDataStore by preferencesDataStore(name = "agent_launch_consent")

@Singleton
class AgentLaunchConsentStoreImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : AgentLaunchConsentStore {
        private val dataStore get() = context.consentDataStore

        override val anyConsent =
            dataStore.data.map { prefs ->
                prefs.asMap().any { (key, value) -> key.name.startsWith("consent.") && value == true }
            }

        override suspend fun hasConsent(key: String): Boolean =
            withContext(ioDispatcher) {
                dataStore.data
                    .map { prefs ->
                        prefs[booleanPreferencesKey(consentKeyName(key))] ?: false
                    }.first()
            }

        override suspend fun setConsent(
            key: String,
            granted: Boolean,
        ) {
            withContext(ioDispatcher) {
                dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(consentKeyName(key))] = granted
                }
            }
        }

        override suspend fun clearByPrefix(prefix: String) {
            withContext(ioDispatcher) {
                dataStore.edit { prefs ->
                    val prefixed = consentKeyName(prefix)
                    prefs
                        .asMap()
                        .keys
                        .filter { it.name.startsWith(prefixed) }
                        .forEach { prefs.remove(it) }
                }
            }
        }

        private fun consentKeyName(key: String): String = "consent.$key"
    }
