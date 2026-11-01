package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.acp.bridge.session.persist.SessionStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class OpenChatsStoreTest {
    private val dir = Files.createTempDirectory("chat-state").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `pruning the chat states beside it never deletes the open chats list`() =
        runTest {
            val io = UnconfinedTestDispatcher(testScheduler)
            val openChats = OpenChatsStore(dir, io)
            val chatStates = SessionStateStore(dir, ioDispatcher = io)
            val open = listOf(OpenChat("srv", "a", "/w", 1L))
            openChats.save(open)
            val foreground = MutableStateFlow(true)
            // Past the 32-chat cap, so every save prunes.
            repeat(33) { id ->
                val ready = MutableStateFlow(SessionSnapshot("s$id", loadState = SessionLoadState.READY))
                val saving = backgroundScope.launch { chatStates.keepSaved("srv/s$id", ready, foreground) { "$id" } }
                runCurrent()
                saving.cancel()
            }

            assertEquals(open, openChats.read())
        }
}
