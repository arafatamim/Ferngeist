package com.tamimarafat.ferngeist.feature.chat.activity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.feature.chat.promptKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.phoneTurnDataStore by preferencesDataStore(name = "ferngeist_phone_turns")

@Serializable
private data class PhoneTurn(
    val serverId: String,
    val sessionId: String,
    val updatedAt: Long,
    // Turn keys, not message ids: a reattached session replays the same prompts under new ids.
    val prompts: List<TurnKey> = emptyList(),
)

/**
 * Phone-origin prompts and their latest observed activity, kept on disk so a restarted process
 * still knows its own turns. Only turns that can still change an [ActivityConfirmation] decision
 * are kept: everything older is pruned on each write, which bounds memory and disk alike.
 */
@Singleton
class PhoneTurnClock(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(context.phoneTurnDataStore, json)

    private val turns = ConcurrentHashMap<Pair<String, String>, PhoneTurn>()

    /** Loads what an earlier process stored; safe to race [record] and [observe]. */
    suspend fun restore() {
        stored(dataStore.data.first()).forEach(::merge)
    }

    suspend fun record(
        serverId: String,
        sessionId: String,
        updatedAt: Long,
        prompts: Set<TurnKey>,
    ) {
        merge(PhoneTurn(serverId, sessionId, updatedAt, prompts.toList()))
        persist(now = updatedAt)
    }

    suspend fun observe(
        serverId: String,
        sessionId: String,
        messages: List<ChatMessage>,
        now: Long,
    ) {
        val before = turns[serverId to sessionId] ?: return
        val updatedAt = latestOwnTurnEnd(before, messages)
        if (updatedAt == before.updatedAt) return
        merge(before.copy(updatedAt = updatedAt, prompts = emptyList()))
        persist(now)
    }

    fun lastTurn(
        serverId: String,
        sessionId: String,
    ): Long? = turns[serverId to sessionId]?.updatedAt

    private fun merge(turn: PhoneTurn) {
        turns.merge(turn.serverId to turn.sessionId, turn) { old, new ->
            // Only the newest prompts can still finish after the latest record; older turns ended before them.
            val prompts = (old.prompts + new.prompts).distinct().takeLast(PROMPTS_KEPT)
            old.copy(updatedAt = maxOf(old.updatedAt, new.updatedAt), prompts = prompts)
        }
    }

    /** Merges disk into memory inside the serialized edit, so a write racing [restore] never drops stored turns. */
    private suspend fun persist(now: Long) {
        val cutoff = now - ActivityConfirmation.RELEVANCE_MS
        dataStore.edit { prefs ->
            stored(prefs).forEach(::merge)
            turns.values.removeIf { it.updatedAt < cutoff }
            write(prefs, turns.values.toList())
        }
    }

    private fun stored(prefs: Preferences): List<PhoneTurn> =
        prefs[keyTurns]?.let { json.decodeFromString<List<PhoneTurn>>(it) }.orEmpty()

    private fun write(
        prefs: MutablePreferences,
        live: List<PhoneTurn>,
    ) {
        if (live.isEmpty()) prefs.remove(keyTurns) else prefs[keyTurns] = json.encodeToString(live)
    }

    private companion object {
        val keyTurns = stringPreferencesKey("turns")
        const val PROMPTS_KEPT = 8
    }
}

private fun latestOwnTurnEnd(
    turn: PhoneTurn,
    messages: List<ChatMessage>,
): Long {
    val keys = messages.promptKeys()
    var ownPrompt = false
    var updatedAt = turn.updatedAt
    for (message in messages) {
        if (message.role == ChatMessage.Role.USER) ownPrompt = keys[message.id] in turn.prompts
        if (ownPrompt && message.role == ChatMessage.Role.ASSISTANT) {
            message.turn?.let { updatedAt = maxOf(updatedAt, it.finishedAt) }
        }
    }
    return updatedAt
}
