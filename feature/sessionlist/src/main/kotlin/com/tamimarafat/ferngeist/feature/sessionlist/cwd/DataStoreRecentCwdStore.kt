package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recentCwdDataStore by preferencesDataStore(name = "recent_cwds")

/**
 * DataStore-backed [RecentCwdStore]. Each target's history is persisted as
 * a JSON array under a `recent_cwd_<targetId>` preferences key.
 *
 * Uses [Json] for serialization and the named DataStore `recent_cwds`
 * to store all targets in a single file.
 */
@Singleton
class DataStoreRecentCwdStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
    ) : RecentCwdStore {

        override fun getRecentCwds(targetId: String): Flow<List<String>> =
            context.recentCwdDataStore.data.map { prefs ->
                prefs[stringPreferencesKey(keyFor(targetId))]
                    ?.let { json.decodeFromString<List<String>>(it) }
                    ?: emptyList()
            }

        override suspend fun addCwd(targetId: String, cwd: String) {
            val normalized = cwd.trim()
            if (normalized.isBlank()) return
            context.recentCwdDataStore.edit { prefs ->
                val key = stringPreferencesKey(keyFor(targetId))
                val current = prefs[key]
                    ?.let { json.decodeFromString<List<String>>(it) }
                    ?: emptyList()
                val updated = (listOf(normalized) + current.filter { it != normalized }).take(10)
                prefs[key] = json.encodeToString(updated)
            }
        }

        override suspend fun removeCwd(targetId: String, cwd: String) {
            context.recentCwdDataStore.edit { prefs ->
                val key = stringPreferencesKey(keyFor(targetId))
                val current = prefs[key]
                    ?.let { json.decodeFromString<List<String>>(it) }
                    ?: return@edit
                val updated = current.filter { it != cwd }
                prefs[key] = json.encodeToString(updated)
            }
        }

        override suspend fun clear(targetId: String) {
            context.recentCwdDataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey(keyFor(targetId)))
            }
        }

        private fun keyFor(targetId: String): String = "recent_cwd_$targetId"
    }
