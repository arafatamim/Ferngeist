package com.tamimarafat.ferngeist.feature.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_RECENT = 5
/**
 * Internal DataStore key prefix used for all recent-selection entries.
 *
 * Full key format: `"recent_selection:" + callerKey`
 * where [callerKey] is the [key] parameter passed to [DataStoreRecentSelectionStore] methods.
 *
 * e.g. `"recent_selection:commands:server-abc:"` or `"recent_selection:config_option:server-abc:opt-1"`
 */
private const val STORE_KEY_PREFIX = "recent_selection:"

private val Context.recentSelectionDataStore by preferencesDataStore(name = "ferngeist_recent_selections")

/**
 * DataStore-backed [RecentSelectionStore].
 *
 * ### Key format convention
 *
 * Callers construct keys with a trailing `:` after each scoping level so that
 * [clearByPrefix] — which uses `startsWith` — does not accidentally match
 * entries belonging to a different server or option.
 *
 * | Context | Example key | Clear prefix | Why trailing `:` |
 * |---------|-------------|--------------|-----------------|
 * | Commands per server | `"commands:$serverId:"` | `"commands:$serverId:"` | Prevents `abc` from clearing `abcd`'s commands |
 * | Config option per server+option | `"config_option:$serverId:$optionId"` | `"config_option:$serverId:"` | The `$optionId` (a UUID) already acts as delimiter |
 *
 * ### Storage layout
 *
 * ```text
 * ferngeist_recent_selections (DataStore)
 * ├── recent_selection:commands:server-1:  -> JSON list (max 5)
 * ├── recent_selection:commands:server-2:  -> JSON list
 * ├── recent_selection:config_option:server-1:opt-a -> JSON list
 * └── ...
 * ```
 */
@Singleton
class DataStoreRecentSelectionStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
    ) : RecentSelectionStore {

        override fun getRecentSelections(key: String): Flow<List<String>> =
            context.recentSelectionDataStore.data.map { prefs ->
                prefs[stringPreferencesKey(STORE_KEY_PREFIX + key)]
                    ?.let { json.decodeFromString<List<String>>(it) }
                    ?: emptyList()
            }

        /**
         * Prepends [value] to the list for [key], removes any duplicate, and caps at [MAX_RECENT].
         *
         * If the current stored list already contains the value, it is moved to the front
         * (MRU bumping) rather than appended.
         */
        override suspend fun addSelection(key: String, value: String) {
            context.recentSelectionDataStore.edit { prefs ->
                val prefKey = stringPreferencesKey(STORE_KEY_PREFIX + key)
                val current = prefs[prefKey]
                    ?.let { json.decodeFromString<List<String>>(it) }
                    ?: emptyList()
                val updated = (listOf(value) + current.filter { it != value }).take(MAX_RECENT)
                prefs[prefKey] = json.encodeToString(updated)
            }
        }

        /**
         * Removes every DataStore key whose full name starts with [STORE_KEY_PREFIX] + [prefix].
         *
         * This is intentionally prefix-based rather than exact-key: when deleting a server we need
         * to wipe all its config-option keys without enumerating every option ID.
         *
         * **Requires** callers to include a trailing delimiter in [prefix] to prevent
         * cross-server collisions. See [RecentSelectionStore.clearByPrefix].
         */
        override suspend fun clearByPrefix(prefix: String) {
            context.recentSelectionDataStore.edit { prefs ->
                val fullPrefix = STORE_KEY_PREFIX + prefix
                val toRemove = prefs.asMap().keys.filter { it.name.startsWith(fullPrefix) }
                toRemove.forEach { prefs.remove(it) }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class RecentSelectionModule {
    @Binds
    @Singleton
    abstract fun bindRecentSelectionStore(impl: DataStoreRecentSelectionStore): RecentSelectionStore
}
