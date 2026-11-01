package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.session.SessionRuntime
import com.tamimarafat.ferngeist.acp.bridge.session.deviceRuntime
import com.tamimarafat.ferngeist.acp.bridge.session.persist.SessionStateStore
import com.tamimarafat.ferngeist.acp.bridge.session.persist.restore
import com.tamimarafat.ferngeist.acp.bridge.session.persist.savedState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * One [SessionRuntime] per chat, which each of the chat's connections attaches to: restored from
 * [store] the first time the chat is asked for, then kept across connections and evictions so a
 * reopen draws at once. Past [maxChats], the least recently asked-for chat no longer [inUse] goes;
 * its saved state stays on disk.
 */
internal class ChatRuntimes(
    private val store: SessionStateStore?,
    private val maxChats: Int,
    private val ioDispatcher: CoroutineDispatcher,
    private val inUse: (chatId: String) -> Boolean,
) {
    private val mutex = Mutex()
    private val runtimes = LinkedHashMap<String, SessionRuntime>(maxChats, LOAD_FACTOR, true)

    /** [chatId]'s runtime, restored from its saved state or empty when it has none. */
    suspend fun runtimeFor(
        chatId: String,
        sessionId: String,
    ): SessionRuntime =
        mutex.withLock {
            runtimes[chatId] ?: (restored(chatId, sessionId) ?: deviceRuntime(sessionId)).also { keep(chatId, it) }
        }

    /** [chatId]'s runtime when it holds anything to draw, without making an empty one. */
    suspend fun existing(
        chatId: String,
        sessionId: String,
    ): SessionRuntime? =
        mutex.withLock {
            runtimes[chatId] ?: restored(chatId, sessionId)?.also { keep(chatId, it) }
        }

    /** [chatId]'s state for [store] to save, or null once the chat is gone. */
    suspend fun savedState(chatId: String): String? = mutex.withLock { runtimes[chatId] }?.savedState()

    /** Drops [chatId]'s runtime and its saved state. */
    suspend fun forget(chatId: String) {
        mutex.withLock { runtimes.remove(chatId) }
        store?.delete(chatId)
    }

    private suspend fun restored(
        chatId: String,
        sessionId: String,
    ): SessionRuntime? {
        val saved = store?.read(chatId) ?: return null
        val runtime = deviceRuntime(sessionId)
        return try {
            withContext(ioDispatcher) { runtime.restore(saved) }
            runtime
        } catch (_: SerializationException) {
            // A state this build cannot read costs only a full load.
            null
        }
    }

    private fun keep(
        chatId: String,
        runtime: SessionRuntime,
    ) {
        runtimes[chatId] = runtime
        val excess = runtimes.size - maxChats
        if (excess > 0) {
            runtimes.keys
                .filter { it != chatId && !inUse(it) }
                .take(excess)
                .forEach(runtimes::remove)
        }
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
