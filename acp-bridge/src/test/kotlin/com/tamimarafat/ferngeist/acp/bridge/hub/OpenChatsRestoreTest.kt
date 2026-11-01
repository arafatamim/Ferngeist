package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.session.persist.SessionStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class OpenChatsRestoreTest {
    @Test
    fun `open chats come back cold after process death and leave once closed`() =
        runTest {
            val dir = Files.createTempDirectory("chat-state").toFile()
            val store = SessionStateStore(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            // A new hub over the same store is the app after a process kill.
            val hubOver = {
                ChatConnectionHub(
                    gatewayRepository = FakeGatewayRepo(),
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    connectivityObserver = ConnectivityObserverStub(initialState = true),
                    stateStore = store,
                )
            }
            hubOver().chatScreenOpened("srv", "s1", "/work")
            advanceUntilIdle()
            val key = chatIdFor("srv", "s1")

            val restarted = hubOver()
            advanceUntilIdle()
            assertEquals(setOf(key), restarted.trackedChatIds.value)
            assertNull("restored chats stay cold until opened", restarted.managerFor(key))
            assertEquals(key, restarted.tapTarget.value?.let { chatIdFor(it.serverId, it.sessionId) })

            restarted.close(key)
            advanceUntilIdle()
            assertEquals(emptySet<String>(), hubOver().trackedChatIds.value)
            dir.deleteRecursively()
        }
}
