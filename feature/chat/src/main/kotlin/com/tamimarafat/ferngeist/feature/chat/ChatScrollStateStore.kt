package com.tamimarafat.ferngeist.feature.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

interface ChatScrollStateStore {
    fun restore(serverId: String, sessionId: String): ChatScrollSnapshot?
    fun save(serverId: String, sessionId: String, snapshot: ChatScrollSnapshot)
    fun clear(serverId: String, sessionId: String)
}

@Singleton
class SharedPreferencesChatScrollStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : ChatScrollStateStore {
    private val sharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun restore(serverId: String, sessionId: String): ChatScrollSnapshot? {
        val prefix = keyPrefix(serverId, sessionId)
        if (!sharedPreferences.contains("${prefix}saved_at")) return null
        return ChatScrollSnapshot(
            anchorMessageId = sharedPreferences.getString("${prefix}anchor_id", null),
            firstVisibleItemIndex = sharedPreferences.getInt("${prefix}index", 0),
            firstVisibleItemScrollOffset = sharedPreferences.getInt("${prefix}offset", 0),
            isFollowing = sharedPreferences.getBoolean("${prefix}following", true),
            savedAt = sharedPreferences.getLong("${prefix}saved_at", 0L),
        )
    }

    override fun save(serverId: String, sessionId: String, snapshot: ChatScrollSnapshot) {
        val prefix = keyPrefix(serverId, sessionId)
        sharedPreferences.edit {
            putString("${prefix}anchor_id", snapshot.anchorMessageId)
                .putInt("${prefix}index", snapshot.firstVisibleItemIndex)
                .putInt("${prefix}offset", snapshot.firstVisibleItemScrollOffset)
                .putBoolean("${prefix}following", snapshot.isFollowing)
                .putLong("${prefix}saved_at", snapshot.savedAt)
        }
    }

    override fun clear(serverId: String, sessionId: String) {
        val prefix = keyPrefix(serverId, sessionId)
        sharedPreferences.edit {
            remove("${prefix}anchor_id")
                .remove("${prefix}index")
                .remove("${prefix}offset")
                .remove("${prefix}following")
                .remove("${prefix}saved_at")
        }
    }

    private fun keyPrefix(serverId: String, sessionId: String): String {
        return "scroll.${serverId}.${sessionId}."
    }

    private companion object {
        const val PREFS_NAME = "ferngeist_chat_scroll"
    }
}

