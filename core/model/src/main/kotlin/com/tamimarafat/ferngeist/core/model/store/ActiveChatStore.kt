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
    val gatewayId: String? = null,
)

/**
 * In-memory record of the chats the user opened, focus-ordered (head = most
 * recent), so the foreground connection notification can deep-link straight
 * back into the chat on screen instead of dropping the user on the home screen.
 *
 * With multiple simultaneous chats the whole list matters: closing one chat
 * promotes the previously-focused one to the head. In-memory is sufficient:
 * the values only matter while connections — and the foreground service that
 * owns the notification — keep the process alive.
 */
class ActiveChatStore {
    private val _openChats = MutableStateFlow<List<ActiveChat>>(emptyList())

    /** All open chats, most recently focused first. */
    val openChats: StateFlow<List<ActiveChat>> = _openChats.asStateFlow()

    private val _activeChat = MutableStateFlow<ActiveChat?>(null)

    /** Head of [openChats] — the chat currently on screen. */
    val activeChat: StateFlow<ActiveChat?> = _activeChat.asStateFlow()

    /** Records [chat] as the focused chat, moving it to the head of the list. */
    fun setActiveChat(chat: ActiveChat) {
        _openChats.update { current -> listOf(chat) + current.filterNot { it.sessionId == chat.sessionId } }
        _activeChat.value = chat
    }

    /** Removes [sessionId]; if it was the head, promotes the next most recent. */
    fun clearIfCurrent(sessionId: String) {
        _openChats.update { current -> current.filterNot { it.sessionId == sessionId } }
        if (_activeChat.value?.sessionId == sessionId) {
            _activeChat.value = _openChats.value.firstOrNull()
        }
    }
}
