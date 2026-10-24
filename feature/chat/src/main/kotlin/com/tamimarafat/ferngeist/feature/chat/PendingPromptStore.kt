package com.tamimarafat.ferngeist.feature.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import com.tamimarafat.ferngeist.core.model.QueuedPromptRecord
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pendingPromptDataStore by preferencesDataStore(name = "ferngeist_pending_prompts")

/**
 * Durability for prompts that were queued for a chat but not yet delivered to the agent.
 *
 * Unlike the transcript itself — which is owned by the server and reloaded on re-entry —
 * a queued prompt exists only locally, so it must outlive the chat screen's view model.
 */
interface PendingPromptStore {
    /** Returns the queued prompts for the chat, in queue order, or an empty list. */
    suspend fun restore(
        serverId: String,
        sessionId: String,
    ): List<QueuedPromptRecord>

    /** Replaces the stored queue for the chat. An empty list removes the entry entirely. */
    suspend fun save(
        serverId: String,
        sessionId: String,
        records: List<QueuedPromptRecord>,
    )
}

/**
 * DataStore-backed [PendingPromptStore].
 *
 * ### Storage layout
 *
 * ```text
 * ferngeist_pending_prompts (DataStore)
 * ├── pending.server-1:session-a -> JSON list of QueuedPromptRecord
 * ├── pending.server-1:session-b -> JSON list
 * └── ...
 * ```
 *
 * One key per chat; a chat with a drained queue has no key at all.
 */
@Singleton
class DataStorePendingPromptStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : PendingPromptStore {
        override suspend fun restore(
            serverId: String,
            sessionId: String,
        ): List<QueuedPromptRecord> =
            withContext(ioDispatcher) {
                context.pendingPromptDataStore.data
                    .map { prefs ->
                        prefs[stringPreferencesKey(queueKey(serverId, sessionId))]
                            ?.let { json.decodeFromString<List<QueuedPromptRecord>>(it) }
                            .orEmpty()
                    }.first()
            }

        override suspend fun save(
            serverId: String,
            sessionId: String,
            records: List<QueuedPromptRecord>,
        ) {
            withContext(ioDispatcher) {
                context.pendingPromptDataStore.edit { prefs ->
                    val prefKey = stringPreferencesKey(queueKey(serverId, sessionId))
                    if (records.isEmpty()) {
                        // A drained queue leaves no residue behind.
                        prefs.remove(prefKey)
                    } else {
                        prefs[prefKey] = json.encodeToString(records)
                    }
                }
            }
        }

        private fun queueKey(
            serverId: String,
            sessionId: String,
        ): String = "pending.$serverId.$sessionId"
    }

@Module
@InstallIn(SingletonComponent::class)
interface PendingPromptModule {
    @Binds
    @Singleton
    fun bindPendingPromptStore(impl: DataStorePendingPromptStore): PendingPromptStore
}
