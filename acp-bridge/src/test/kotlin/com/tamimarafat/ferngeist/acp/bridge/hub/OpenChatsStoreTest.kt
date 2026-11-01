package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
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
            val persistence = HubPersistence(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            val open = listOf(OpenChat("srv", "a", "/w", 1L))
            persistence.openChats.save(open)
            // Past the 32-chat cap, so every save prunes.
            repeat(33) { id ->
                val ready = MutableStateFlow(SessionSnapshot("s$id", loadState = SessionLoadState.READY))
                val saving = backgroundScope.launch { persistence.chatStates.keepSaved("srv/s$id", ready) { "$id" } }
                runCurrent()
                saving.cancel()
            }

            assertEquals(open, persistence.openChats.read())
        }
}
