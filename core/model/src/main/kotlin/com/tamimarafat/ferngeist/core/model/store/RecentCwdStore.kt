package com.tamimarafat.ferngeist.core.model.store

import kotlinx.coroutines.flow.Flow

/**
 * Per-target MRU working-directory history. Each target maintains an
 * independent list of up to 10 recent CWDs, ordered most-recent-first.
 *
 * Implementations must persist entries across process restarts and
 * handle deduplication, MRU bumping, and the 10-entry cap.
 */
interface RecentCwdStore {
    /** Emits the current recent-CWD list for [targetId], MRU-first. */
    fun getRecentCwds(targetId: String): Flow<List<String>>

    /** Adds or bumps [cwd] to the top of the [targetId] list. No-ops on blank input. */
    suspend fun addCwd(targetId: String, cwd: String)

    /** Removes [cwd] from the [targetId] list. No-op if not present. */
    suspend fun removeCwd(targetId: String, cwd: String)

    /** Removes all entries for [targetId]. */
    suspend fun clear(targetId: String)
}
