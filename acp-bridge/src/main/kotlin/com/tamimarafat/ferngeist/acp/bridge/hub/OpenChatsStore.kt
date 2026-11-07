package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.session.persist.replaceAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** The hub's open chats as one file in [dir], so the switcher survives process death. */
internal class OpenChatsStore(
    dir: File,
    private val ioDispatcher: CoroutineDispatcher,
) {
    // Not the chat states' `.json`: their pruning must never count or delete the list.
    private val file = File(dir, "open-chats-v$VERSION.list")

    /**
     * The open chats [save] last saved; a corrupt list costs only the switcher's entries.
     * Null when the file cannot be read, so the caller leaves it alone instead of overwriting it.
     */
    suspend fun read(): List<OpenChat>? =
        withContext(ioDispatcher) {
            try {
                val saved = file.takeIf { it.exists() }?.readText() ?: return@withContext emptyList()
                Json.decodeFromString<List<OpenChat>>(saved)
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IOException) {
                null
            }
        }

    /** Best-effort: a failed write keeps the previous list, and the next change rewrites it. */
    suspend fun save(chats: List<OpenChat>) {
        withContext(ioDispatcher) {
            try {
                file.replaceAtomically(Json.encodeToString(chats))
            } catch (_: IOException) {
                // Rewritten by the next change.
            }
        }
    }

    private companion object {
        /** Bump when [OpenChat]'s shape changes incompatibly. */
        const val VERSION = 1
    }
}

/** One chat the hub tracked, as much as a cold entry needs to come back after process death. */
@Serializable
internal data class OpenChat(
    val serverId: String,
    val sessionId: String,
    val cwd: String,
    val lastFocusedMs: Long,
)
