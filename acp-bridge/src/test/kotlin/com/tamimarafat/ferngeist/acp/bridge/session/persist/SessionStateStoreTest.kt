package com.tamimarafat.ferngeist.acp.bridge.session.persist

import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateStoreTest {
    private val dir = Files.createTempDirectory("chat-state").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun TestScope.store(maxChats: Int = 32) =
        SessionStateStore(dir, maxChats, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.save(
        store: SessionStateStore,
        chatId: String,
        state: String,
    ) {
        val ready = MutableStateFlow(SessionSnapshot(chatId, loadState = SessionLoadState.READY))
        val saving = backgroundScope.launch { store.keepSaved(chatId, ready) { state } }
        runCurrent()
        saving.cancel()
    }

    @Test
    fun `a saved state reads back after process death and is gone once deleted`() =
        runTest {
            save(store(), "srv/s1", """{"x":1}""")

            val reopened = store()
            assertEquals("""{"x":1}""", reopened.read("srv/s1"))
            reopened.delete("srv/s1")
            assertNull(reopened.read("srv/s1"))
        }

    @Test
    fun `storage keeps only the most recently saved chats`() =
        runTest {
            val store = store(maxChats = 2)
            listOf("a", "b", "c").forEach { id ->
                save(store, "srv/$id", id)
                // File times resolve to the second on some filesystems; keep the order unambiguous.
                dir.listFiles()!!.forEach { if (it.name.contains("srv%2F$id")) it.setLastModified(id[0].code * 1_000L) }
            }

            assertNull("the oldest chat is pruned", store.read("srv/a"))
            assertEquals("c", store.read("srv/c"))
        }
}
