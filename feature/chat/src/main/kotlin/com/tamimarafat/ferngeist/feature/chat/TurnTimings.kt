package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.core.model.TurnTiming
import com.tamimarafat.ferngeist.core.model.content

/** The reply that closes each turn, by id, to its turn: the last assistant message before the next prompt. */
internal fun List<ChatMessage>.turnKeys(): Map<String, TurnKey> {
    val replies = HashMap<TurnKey, String>()
    var turn: TurnKey? = null
    var prompts = 0
    forEach { message ->
        when (message.role) {
            ChatMessage.Role.USER -> turn = TurnKey(prompts++, message.content.trim().hashCode())
            ChatMessage.Role.ASSISTANT -> turn?.let { replies[it] = message.id }
            ChatMessage.Role.SYSTEM -> Unit
        }
    }
    return replies.entries.associate { (key, id) -> id to key }
}

/** Puts [stored] times back on replies a replay brought back without them. */
internal fun List<ChatMessage>.withStoredTurns(
    keys: Map<String, TurnKey>,
    stored: Map<TurnKey, TurnTiming>,
): List<ChatMessage> {
    if (stored.isEmpty()) return this
    return map { message ->
        if (message.turn != null) return@map message
        keys[message.id]?.let(stored::get)?.let { message.copy(turn = it) } ?: message
    }
}

/** Times stamped live that [stored] does not hold yet. */
internal fun List<ChatMessage>.unstoredTurns(
    keys: Map<String, TurnKey>,
    stored: Map<TurnKey, TurnTiming>,
): Map<TurnKey, TurnTiming> =
    mapNotNull { message ->
        val key = keys[message.id] ?: return@mapNotNull null
        message.turn?.takeIf { stored[key] != it }?.let { key to it }
    }.toMap()
