package com.tamimarafat.ferngeist.acp.bridge.session.persist

import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * One file per chat holding its runtime's saved state, so a chat draws at once after process
 * death. Files, not Room: a long chat's state outgrows what a cursor window reads back. Beside
 * them, one file lists the hub's open chats, so the switcher survives process death too.
 *
 * The format version is part of the file name, so a state saved by an older layout is never
 * read: it only costs that chat a full load, and pruning deletes it. Only the [maxChats] most
 * recently saved chats are kept.
 */
class SessionStateStore(
    private val dir: File,
    private val maxChats: Int = 32,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun read(chatId: String): String? =
        withContext(ioDispatcher) { file(chatId).takeIf { it.exists() }?.readText() }

    suspend fun delete(chatId: String) {
        withContext(ioDispatcher) { file(chatId).delete() }
    }

    /**
     * The open chats [saveOpenChats] last saved; a corrupt list costs only the switcher's entries.
     * Null when the file cannot be read, so the caller leaves it alone instead of overwriting it.
     */
    internal suspend fun readOpenChats(): List<OpenChat>? =
        withContext(ioDispatcher) {
            try {
                val saved = openChatsFile().takeIf { it.exists() }?.readText() ?: return@withContext emptyList()
                Json.decodeFromString<List<OpenChat>>(saved)
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IOException) {
                null
            }
        }

    /** Best-effort: a failed write keeps the previous list, and the next change rewrites it. */
    internal suspend fun saveOpenChats(chats: List<OpenChat>) {
        withContext(ioDispatcher) { ignoringIoFailure { replace(openChatsFile(), Json.encodeToString(chats)) } }
    }

    /**
     * Saves [chatId]'s state each time [snapshot] settles on a new ready state, at most every
     * [SAVE_INTERVAL_MS]: a process death loses at most that much, which a reattach refills.
     * shortcut: rewrites the whole state per save; a multi-MB chat streaming on screen pays an
     * encode every interval (off the hub's main-thread scope), so save less often if that shows in profiles.
     */
    suspend fun keepSaved(
        chatId: String,
        snapshot: StateFlow<SessionSnapshot>,
        save: suspend () -> String?,
    ) {
        snapshot.filter { it.loadState == SessionLoadState.READY }.collect {
            withContext(ioDispatcher) { ignoringIoFailure { save()?.let { state -> write(chatId, state) } } }
            delay(SAVE_INTERVAL_MS)
        }
    }

    /** Replaces [chatId]'s state atomically: a reader sees the old file or the new one, never half. */
    private fun write(
        chatId: String,
        state: String,
    ) {
        replace(file(chatId), state)
        prune()
    }

    private fun replace(
        target: File,
        text: String,
    ) {
        dir.mkdirs()
        val temp = File.createTempFile("state", ".tmp", dir)
        temp.writeText(text)
        if (!temp.renameTo(target)) temp.delete()
    }

    private fun prune() {
        dir
            .listFiles { file -> file.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxChats)
            ?.forEach { it.delete() }
    }

    private fun file(chatId: String) =
        File(dir, "v$SAVED_STATE_FORMAT_VERSION-${URLEncoder.encode(chatId, "UTF-8")}$SUFFIX")

    // These writes run in the hub's own coroutines, where a throw would end the chat's
    // observer; a lost save only costs a reattach refill.
    private inline fun ignoringIoFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            // Retried by the next save.
        }
    }

    // Not [SUFFIX]: pruning must never count or delete the list.
    private fun openChatsFile() = File(dir, "open-chats-v$OPEN_CHATS_VERSION.list")

    private companion object {
        /** Bump when [OpenChat]'s shape changes incompatibly. */
        const val OPEN_CHATS_VERSION = 1
        const val SUFFIX = ".json"
        const val SAVE_INTERVAL_MS = 3_000L
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
