package com.tamimarafat.ferngeist.core.model.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Navigation context for the chat session the user most recently opened. */
data class ActiveChat(
    val serverId: String,
    val sessionId: String,
    val cwd: String,
    val title: String,
)

/**
 * In-memory record of the chat the user most recently opened, so the foreground
 * connection notification can deep-link straight back to that session instead of
 * dropping the user on the home screen.
 *
 * In-memory is sufficient: the value only matters while the connection — and the
 * foreground service that owns the notification — keep the process alive.
 */
class ActiveChatStore {
    private val _activeChat = MutableStateFlow<ActiveChat?>(null)
    val activeChat: StateFlow<ActiveChat?> = _activeChat.asStateFlow()

    /** Records [chat] as the currently-open chat. */
    fun setActiveChat(chat: ActiveChat) {
        _activeChat.value = chat
    }

    /** Clears the record only if it still points at [sessionId]. */
    fun clearIfCurrent(sessionId: String) {
        _activeChat.update { current -> if (current?.sessionId == sessionId) null else current }
    }
}
