package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.session.persist.SessionStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Where the hub keeps its chats across process death, in [dir]: each chat's state, and which were open. */
class HubPersistence(
    dir: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal val chatStates = SessionStateStore(dir, ioDispatcher = ioDispatcher)
    internal val openChats = OpenChatsStore(dir, ioDispatcher)
}
