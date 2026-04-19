package com.tamimarafat.ferngeist.feature.serverlist.consent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentLaunchConsentStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentLaunchConsentStore {

    private val preferences by lazy {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun hasConsent(key: String): Boolean = withContext(Dispatchers.IO) {
        preferences.getBoolean(storageKey(key), false)
    }

    override suspend fun setConsent(key: String, granted: Boolean) = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean(storageKey(key), granted).apply()
    }

    override suspend fun clearByPrefix(prefix: String) = withContext(Dispatchers.IO) {
        val storagePrefix = storageKey(prefix)
        val keys = preferences.all.keys.filter { key ->
            key.startsWith(storagePrefix)
        }
        if (keys.isEmpty()) {
            return@withContext
        }
        preferences.edit().apply {
            keys.forEach { remove(it) }
        }.apply()
    }

    private fun storageKey(key: String): String = "launch-consent:$key"

    private companion object {
        private const val FILE_NAME = "agent_launch_consent"
    }
}
