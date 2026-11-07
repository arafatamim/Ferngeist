package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.acp.bridge.session.persist.SessionStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Keeps the hub's chats across process death, in [dir]: each chat's state, saved as it settles,
 * and the list of open chats, so the switcher comes back too.
 */
class HubPersistence(
    dir: File,
    private val appInForeground: StateFlow<Boolean>,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal val chatStates = SessionStateStore(dir, ioDispatcher = ioDispatcher)
    private val openChatsStore = OpenChatsStore(dir, ioDispatcher)
    private val openChats = MutableStateFlow(emptyList<OpenChat>())

    /**
     * Hands the chats open before process death to [onRestored], then keeps the [track]ed list saved.
     * shortcut: an unreadable list is left as it is and not saved this process, rather than
     * overwritten with only the chats opened since; retry the read if that proves transient.
     */
    internal suspend fun restoreOpenChats(onRestored: (List<OpenChat>) -> Unit) {
        val saved = openChatsStore.read() ?: return
        onRestored(saved)
        openChats.collect { openChatsStore.save(it) }
    }

    /** The hub's open chats now, most recently focused first. */
    internal fun track(chats: List<OpenChat>) {
        openChats.value = chats
    }

    /** Saves [chatId]'s state, read by [state], when [snapshot] settles; see [SessionStateStore.keepSaved]. */
    internal suspend fun keepSaved(
        chatId: String,
        snapshot: StateFlow<SessionSnapshot>,
        state: suspend () -> String?,
    ) = chatStates.keepSaved(chatId, snapshot, appInForeground, state)
}
