package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.persist.SessionStateStore
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class OpenChatsRestoreTest {
    @Test
    fun `open chats come back cold after process death and leave once closed`() =
        runTest {
            val dir = Files.createTempDirectory("chat-state").toFile()
            val store = SessionStateStore(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            hubOver(store).chatScreenOpened("srv", "s1", "/work")
            advanceUntilIdle()
            val key = chatIdFor("srv", "s1")

            val restarted = hubOver(store)
            advanceUntilIdle()
            assertEquals(setOf(key), restarted.trackedChatIds.value)
            assertNull("restored chats stay cold until opened", restarted.managerFor(key))
            assertEquals(key, restarted.tapTarget.value?.let { chatIdFor(it.serverId, it.sessionId) })

            restarted.close(key)
            advanceUntilIdle()
            assertEquals(emptySet<String>(), hubOver(store).trackedChatIds.value)
            dir.deleteRecursively()
        }

    @Test
    fun `restored chats hold no transport so a new chat does not evict them`() =
        runTest {
            val dir = Files.createTempDirectory("chat-state").toFile()
            val store = SessionStateStore(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            val before = hubOver(store)
            listOf("s1", "s2", "s3").forEach { before.chatScreenOpened("srv", it, "/work") }
            advanceUntilIdle()

            val restarted = hubOver(store)
            advanceUntilIdle()
            restarted.registerChat(sessionId = "s4")
            advanceUntilIdle()

            val expected = listOf("s1", "s2", "s3", "s4").mapTo(HashSet()) { chatIdFor("srv", it) }
            assertEquals(expected, hubOver(store).trackedChatIds.value)
            dir.deleteRecursively()
        }

    @Test
    fun `closing a restored chat deletes the gateway session that runs it`() =
        runTest {
            val dir = Files.createTempDirectory("chat-state").toFile()
            val store = SessionStateStore(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            hubOver(store).chatScreenOpened("srv", "s1", "/work")
            advanceUntilIdle()
            val running = GatewaySessionSummary("g-1", "r-1", "agent-1", "active", acpSessionIds = listOf("s1"))
            val repo = FakeGatewayRepo().apply { sessions = listOf(running) }

            val restarted = hubOver(store, repo)
            advanceUntilIdle()
            val closed = restarted.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred"))

            assertTrue(closed)
            assertEquals(listOf("g-1"), repo.closed)
            assertEquals(emptySet<String>(), restarted.trackedChatIds.value)
            dir.deleteRecursively()
        }

    @Test
    fun `a restored chat stays tracked when the gateway listing fails`() =
        runTest {
            val dir = Files.createTempDirectory("chat-state").toFile()
            val store = SessionStateStore(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            hubOver(store).chatScreenOpened("srv", "s1", "/work")
            advanceUntilIdle()
            val repo = FakeGatewayRepo().apply { listFailure = IOException("offline") }

            val restarted = hubOver(store, repo)
            advanceUntilIdle()
            runCatching { restarted.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred")) }
            advanceUntilIdle()

            assertEquals(setOf(chatIdFor("srv", "s1")), hubOver(store).trackedChatIds.value)
            dir.deleteRecursively()
        }

    @Test
    fun `a restored chat opened and closed offline stays in the switcher`() =
        runTest {
            val dir = Files.createTempDirectory("chat-state").toFile()
            val store = SessionStateStore(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            hubOver(store).chatScreenOpened("srv", "s1", "/work")
            advanceUntilIdle()

            val restarted = hubOver(store)
            advanceUntilIdle()
            restarted.chatScreenOpened("srv", "s1", "/work")
            restarted.chatScreenClosed("srv", "s1")
            advanceUntilIdle()

            assertEquals(setOf(chatIdFor("srv", "s1")), hubOver(store).trackedChatIds.value)
            dir.deleteRecursively()
        }

    @Test
    fun `an unreadable open-chat list is not overwritten by the chats opened after it`() =
        runTest {
            val dir = Files.createTempDirectory("chat-state").toFile()
            val store = SessionStateStore(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            hubOver(store).chatScreenOpened("srv", "s1", "/work")
            advanceUntilIdle()
            val list = dir.listFiles { file -> file.name.endsWith(".list") }!!.single()
            list.setReadable(false)
            // A privileged runner reads through the permission; there is no unreadable file to test.
            assumeFalse(list.canRead())

            hubOver(store).chatScreenOpened("srv", "s2", "/work")
            advanceUntilIdle()
            list.setReadable(true)

            assertEquals(setOf(chatIdFor("srv", "s1")), hubOver(store).trackedChatIds.value)
            dir.deleteRecursively()
        }

    /** A new hub over the same store is the app after a process kill. */
    @Test
    fun `a chat saved before process death paints from the runtime its connection then attaches to`() =
        runTest {
            val dir = Files.createTempDirectory("chat-state").toFile()
            val store = SessionStateStore(dir, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
            val before = hubOver(store)
            val runtime = before.runtimeFor("srv", "s1")
            runtime.onEvent(AppSessionEvent.SessionInfoUpdated(title = "kept", updatedAt = null))
            before.registerChat(sessionId = "s1", snapshot = runtime.snapshot)
            advanceUntilIdle()

            val restarted = hubOver(store)
            assertEquals("kept", restarted.lastSnapshot("srv", "s1")?.title)
            val attached = restarted.runtimeFor("srv", "s1")
            assertEquals("kept", attached.snapshot.value.title)
            assertNull("an unsaved chat has nothing to paint", restarted.lastSnapshot("srv", "s2"))
            dir.deleteRecursively()
        }

    private fun TestScope.hubOver(
        store: SessionStateStore,
        repo: FakeGatewayRepo = FakeGatewayRepo(),
    ) = ChatConnectionHub(
        gatewayRepository = repo,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        connectivityObserver = ConnectivityObserverStub(initialState = true),
        stateStore = store,
    )
}
