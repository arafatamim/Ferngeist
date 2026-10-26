package com.tamimarafat.ferngeist.feature.chat.timing

import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.core.model.TurnTiming
import com.tamimarafat.ferngeist.core.model.repository.TurnTimingRepository
import com.tamimarafat.ferngeist.feature.chat.turnKeys
import com.tamimarafat.ferngeist.feature.chat.unstoredTurns
import com.tamimarafat.ferngeist.feature.chat.withStoredTurns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a chat's turn times on the replies they belong to. A replay carries no times, so turns
 * are recorded as they finish and put back on the replayed replies that match them.
 */
internal class ChatTurnTimings(
    private val serverId: String,
    private val scope: CoroutineScope,
    private val repository: TurnTimingRepository,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Times for the session the last reply needed them for, so later replies skip the store. */
    private var loaded: LoadedTimings? = null

    private class LoadedTimings(
        val sessionId: String,
        val timings: Map<TurnKey, TurnTiming>,
    )

    suspend fun withTurnTimings(
        sessionId: String,
        messages: List<ChatMessage>,
    ): List<ChatMessage> {
        val keys = messages.turnKeys()
        if (keys.isEmpty()) return messages
        val known =
            loaded?.takeIf { it.sessionId == sessionId }?.timings
                ?: withContext(ioDispatcher) { repository.getTimings(serverId, sessionId) }
        val fresh = messages.unstoredTurns(keys, known)
        val stored = known + fresh
        loaded = LoadedTimings(sessionId, stored)
        if (fresh.isNotEmpty()) {
            scope.launch(ioDispatcher) {
                fresh.forEach { (key, timing) -> repository.record(serverId, sessionId, key, timing) }
            }
        }
        return messages.withStoredTurns(keys, stored)
    }
}
