package com.tamimarafat.ferngeist.feature.chat

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.chatScrollDataStore by preferencesDataStore(name = "ferngeist_chat_scroll")

interface ChatScrollStateStore {
    suspend fun restore(
        serverId: String,
        sessionId: String,
    ): ChatScrollSnapshot?

    suspend fun save(
        serverId: String,
        sessionId: String,
        snapshot: ChatScrollSnapshot,
    )

    suspend fun clear(
        serverId: String,
        sessionId: String,
    )
}

@Singleton
class DataStoreChatScrollStateStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ChatScrollStateStore {

        override suspend fun restore(
            serverId: String,
            sessionId: String,
        ): ChatScrollSnapshot? =
            withContext(Dispatchers.IO) {
                context.chatScrollDataStore.data
                    .map { prefs ->
                        val prefix = "scroll.$serverId.$sessionId."
                        val savedAt = prefs[longPreferencesKey("${prefix}savedAt")] ?: return@map null
                        ChatScrollSnapshot(
                            anchorMessageId = prefs[stringPreferencesKey("${prefix}anchor")],
                            firstVisibleItemIndex = prefs[intPreferencesKey("${prefix}index")] ?: 0,
                            firstVisibleItemScrollOffset = prefs[intPreferencesKey("${prefix}offset")] ?: 0,
                            isFollowing = prefs[booleanPreferencesKey("${prefix}following")] ?: true,
                            savedAt = savedAt,
                        )
                    }.first()
            }

        override suspend fun save(
            serverId: String,
            sessionId: String,
            snapshot: ChatScrollSnapshot,
        ) {
            withContext(Dispatchers.IO) {
                context.chatScrollDataStore.edit { prefs ->
                    val prefix = "scroll.$serverId.$sessionId."
                    val anchorKey = stringPreferencesKey("${prefix}anchor")
                    if (snapshot.anchorMessageId != null) {
                        prefs[anchorKey] = snapshot.anchorMessageId
                    } else {
                        prefs.remove(anchorKey)
                    }
                    prefs[intPreferencesKey("${prefix}index")] = snapshot.firstVisibleItemIndex
                    prefs[intPreferencesKey("${prefix}offset")] = snapshot.firstVisibleItemScrollOffset
                    prefs[booleanPreferencesKey("${prefix}following")] = snapshot.isFollowing
                    prefs[longPreferencesKey("${prefix}savedAt")] = snapshot.savedAt
                }
            }
        }

        override suspend fun clear(
            serverId: String,
            sessionId: String,
        ) {
            withContext(Dispatchers.IO) {
                context.chatScrollDataStore.edit { prefs ->
                    val prefix = "scroll.$serverId.$sessionId."
                    prefs.remove(stringPreferencesKey("${prefix}anchor"))
                    prefs.remove(intPreferencesKey("${prefix}index"))
                    prefs.remove(intPreferencesKey("${prefix}offset"))
                    prefs.remove(booleanPreferencesKey("${prefix}following"))
                    prefs.remove(longPreferencesKey("${prefix}savedAt"))
                }
            }
        }
    }
