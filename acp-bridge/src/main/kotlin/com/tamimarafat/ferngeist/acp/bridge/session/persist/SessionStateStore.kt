package com.tamimarafat.ferngeist.acp.bridge.session.persist

import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * One file per chat holding its runtime's saved state, so a chat draws at once after process
 * death. Files, not Room: a long chat's state outgrows what a cursor window reads back.
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
     * Saves [chatId]'s state when its ready snapshot settles (a load or turn ends, or the user
     * stops it) and when the app leaves the foreground, where Android may kill it. Not per update:
     * each save rewrites the whole state, so a process death mid-turn loses that turn's tail,
     * which a reattach refills.
     */
    suspend fun keepSaved(
        chatId: String,
        snapshot: StateFlow<SessionSnapshot>,
        appInForeground: StateFlow<Boolean>,
        save: suspend () -> String?,
    ) {
        val settled =
            snapshot
                .filter { it.loadState == SessionLoadState.READY }
                .map { it.isStreaming }
                .distinctUntilChanged()
                .filter { streaming -> !streaming }
        val backgrounded =
            appInForeground.drop(1).filter { foreground ->
                !foreground && snapshot.value.loadState == SessionLoadState.READY
            }
        merge(settled, backgrounded).collect {
            withContext(ioDispatcher) { ignoringIoFailure { save()?.let { state -> write(chatId, state) } } }
        }
    }

    /** Replaces [chatId]'s state atomically: a reader sees the old file or the new one, never half. */
    private fun write(
        chatId: String,
        state: String,
    ) {
        file(chatId).replaceAtomically(state)
        prune()
    }

    // Only state files: the hub's open-chat list shares this directory and must never be pruned.
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

    private companion object {
        const val SUFFIX = ".json"
    }
}

/** Replaces this file's text atomically: a reader sees the old file or the new one, never half. */
internal fun File.replaceAtomically(text: String) {
    // The temp file sits beside the target, so the rename stays on one filesystem.
    val dir = checkNotNull(absoluteFile.parentFile).apply { mkdirs() }
    val temp = File.createTempFile("state", ".tmp", dir)
    temp.writeText(text)
    if (!temp.renameTo(this)) temp.delete()
}
