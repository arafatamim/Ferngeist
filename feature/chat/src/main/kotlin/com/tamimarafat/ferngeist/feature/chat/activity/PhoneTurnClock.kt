package com.tamimarafat.ferngeist.feature.chat.activity

import com.tamimarafat.ferngeist.core.model.ChatMessage
import java.util.concurrent.ConcurrentHashMap

/** Phone-origin prompts and their latest observed activity, held only in process memory. */
internal object PhoneTurnClock {
    private data class Activity(
        val updatedAt: Long,
        val promptIds: Set<String>,
    )

    private val turns = ConcurrentHashMap<Pair<String, String>, Activity>()

    fun record(
        serverId: String,
        sessionId: String,
        updatedAt: Long,
        promptIds: Set<String>,
    ) {
        turns.compute(serverId to sessionId) { _, previous ->
            Activity(maxOf(previous?.updatedAt ?: updatedAt, updatedAt), previous?.promptIds.orEmpty() + promptIds)
        }
    }

    fun observe(
        serverId: String,
        sessionId: String,
        messages: List<ChatMessage>,
    ) {
        turns.computeIfPresent(serverId to sessionId) { _, activity ->
            var ownPrompt = false
            var updatedAt = activity.updatedAt
            for (message in messages) {
                if (message.role == ChatMessage.Role.USER) ownPrompt = message.id in activity.promptIds
                if (ownPrompt && message.role == ChatMessage.Role.ASSISTANT) {
                    message.turn?.let { updatedAt = maxOf(updatedAt, it.finishedAt) }
                }
            }
            activity.copy(updatedAt = updatedAt)
        }
    }

    fun lastTurn(
        serverId: String,
        sessionId: String,
    ): Long? = turns[serverId to sessionId]?.updatedAt
}
