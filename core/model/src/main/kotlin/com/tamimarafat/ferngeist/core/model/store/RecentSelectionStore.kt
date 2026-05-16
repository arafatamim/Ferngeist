package com.tamimarafat.ferngeist.core.model.store

import kotlinx.coroutines.flow.Flow

interface RecentSelectionStore {
    fun getRecentSelections(key: String): Flow<List<String>>
    suspend fun addSelection(key: String, value: String)

    /**
     * Removes all stored entries whose full DataStore key starts with [prefix].
     *
     * Full key format: `"recent_selection:" + prefix`
     *
     * Callers MUST include a trailing delimiter in [prefix] (e.g. `"commands:$serverId:"`)
     * to prevent a server ID like `abc` from also clearing keys for `abcd`.
     *
     * @see [DataStoreRecentSelectionStore.clearByPrefix] for the `startsWith` implementation.
     */
    suspend fun clearByPrefix(prefix: String)
}
