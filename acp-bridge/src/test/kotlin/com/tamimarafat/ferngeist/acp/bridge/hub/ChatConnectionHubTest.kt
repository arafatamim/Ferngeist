package com.tamimarafat.ferngeist.acp.bridge.hub

import app.cash.turbine.test
import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.GatewaySessionResumeRejectedException
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConnectionHubTest {
    @Test
    fun `resolveSessionTitle is null while no transport is warm`() =
        runTest {
            val hub = newHub()

            val title = hub.resolveSessionTitle(serverId = "srv", sessionId = "s1", cwd = "/w")

            assertNull(title)
        }

    @Test
    fun `resolveSessionTitle swallows a listing failure instead of raising it into the chat`() =
        runTest {
            val hub = newHub()
            // Warm transport that is registered as connected but holds no SDK client,
            // so the listing call fails the way a half-open socket does.
            val manager = hub.acquireChatManager()
            hub.registerChat(sessionId = "s1", manager = manager)
            manager.forceConnectionState(AcpConnectionState.Connected)

            val title = hub.resolveSessionTitle(serverId = "srv", sessionId = "s1", cwd = "/w")

            assertNull(title)
        }

    @Test
    fun `register returns stable chatIds for same server and session`() =
        runTest {
            val hub = newHub()
            val first = hub.registerChat(sessionId = "s1")
            val second = hub.registerChat(sessionId = "s1", gatewaySessionId = null)
            assertEquals(first, second)
            assertTrue(hub.isTracked("srv", "s1"))
            assertEquals(1, hub.trackedCount())
        }

    @Test
    fun `a screen torn down after its replacement opened leaves the chat on screen`() =
        runTest {
            val hub = newHub()
            // A fast A to B to A switch: the new A screen opens before the old one is cleared.
            hub.chatScreenOpened("srv", "s1", "/w")
            hub.chatScreenOpened("srv", "s1", "/w")
            hub.chatScreenClosed("srv", "s1")

            assertEquals("s1", hub.onScreenChat.value?.sessionId)
            hub.chatScreenClosed("srv", "s1")
            assertNull(hub.onScreenChat.value)
        }

    @Test
    fun `chatScreenClosed on a chat that never attached a transport drops tracking`() =
        runTest {
            val hub = newHub()
            hub.chatScreenOpened("srv", "s1", "/w")
            assertTrue(hub.isTracked("srv", "s1"))

            hub.chatScreenClosed("srv", "s1")
            assertFalse(hub.isTracked("srv", "s1"))
            assertEquals(0, hub.trackedCount())
        }

    @Test
    fun `fourth registration evicts the least recently focused idle entry`() =
        runTest {
            val hub = newHub()
            hub.registerChat(sessionId = "a")
            hub.registerChat(sessionId = "b")
            hub.registerChat(sessionId = "c")
            hub.registerChat(sessionId = "d")
            assertFalse(hub.isTracked("srv", "a"))
            assertTrue(hub.isTracked("srv", "b"))
            assertTrue(hub.isTracked("srv", "c"))
            assertTrue(hub.isTracked("srv", "d"))
            assertEquals(3, hub.trackedCount())
        }

    @Test
    fun `streaming entries are never evicted even over cap`() =
        runTest {
            val hub = newHub()
            hub.registerChat(sessionId = "a", streaming = true)
            hub.registerChat(sessionId = "b", streaming = true)
            hub.registerChat(sessionId = "c", streaming = true)
            hub.registerChat(sessionId = "d")
            // d itself is the only idle candidate; nothing else may be evicted
            assertTrue(hub.isTracked("srv", "a"))
            assertTrue(hub.isTracked("srv", "b"))
            assertTrue(hub.isTracked("srv", "c"))
            assertTrue(hub.isTracked("srv", "d"))
            assertEquals(4, hub.trackedCount())
        }

    @Test
    fun `mixed cap eviction skips streaming and takes oldest idle`() =
        runTest {
            val hub = newHub()
            hub.registerChat(sessionId = "a") // idle, oldest
            hub.registerChat(sessionId = "b", streaming = true)
            hub.registerChat(sessionId = "c", streaming = true)
            hub.registerChat(sessionId = "d")
            assertFalse(hub.isTracked("srv", "a"))
            assertTrue(hub.isTracked("srv", "b"))
            assertTrue(hub.isTracked("srv", "c"))
            assertTrue(hub.isTracked("srv", "d"))
        }

    @Test
    fun `all-streaming pool below the device cap tolerates the over-cap`() =
        runTest {
            val hub = newHub(maxHot = 3, maxGateway = 5)
            hub.registerChat(sessionId = "a", streaming = true)
            hub.registerChat(sessionId = "b", streaming = true)
            hub.registerChat(sessionId = "c", streaming = true)
            hub.registerChat(sessionId = "d", streaming = true)
            // Nothing is evictable and the pool still fits the gateway lease: the
            // count grows past maxHotConnections so background turns survive.
            listOf("a", "b", "c", "d").forEach { assertTrue(hub.isTracked("srv", it)) }
            assertEquals(4, hub.trackedCount())
        }

    @Test
    fun `all-streaming pool at the device cap evicts the least recently focused`() =
        runTest {
            val hub = newHub(maxHot = 3, maxGateway = 4)
            hub.registerChat(sessionId = "a", streaming = true)
            hub.registerChat(sessionId = "b", streaming = true)
            hub.registerChat(sessionId = "c", streaming = true)
            hub.registerChat(sessionId = "d", streaming = true)
            hub.registerChat(sessionId = "e", streaming = true)
            // a is the oldest and the pool is at the gateway lease: the over-cap
            // cannot survive, so a is evicted to keep the count bounded.
            assertFalse(hub.isTracked("srv", "a"))
            listOf("b", "c", "d", "e").forEach { assertTrue(hub.isTracked("srv", it)) }
            assertEquals(4, hub.trackedCount())
        }

    @Test
    fun `resumeAttachToken routes the resume and returns its fresh token`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo)

            val token = hub.resumeAttachToken(gatewayConfig(), AcpConnectionConfig.ResumeInfo("s1", "old-token"))

            assertEquals("fresh-token", token)
            assertEquals(listOf("s1"), repo.resumedSessions)
        }

    @Test
    fun `resumeAttachToken maps a 400 to GatewaySessionResumeRejectedException`() =
        runTest {
            val repo = FakeGatewayRepo()
            repo.resumeFailure = GatewayRequestException(400, "session not found", null)
            val hub = newHub(repo)

            try {
                hub.resumeAttachToken(gatewayConfig(), AcpConnectionConfig.ResumeInfo("s1", "old-token"))
                fail("expected GatewaySessionResumeRejectedException")
            } catch (expected: GatewaySessionResumeRejectedException) {
                assertEquals("session not found", expected.message)
            }
        }

    @Test
    fun `resumeAttachToken leaves non-400 failures as gateway errors`() =
        runTest {
            val repo = FakeGatewayRepo()
            repo.resumeFailure = GatewayRequestException(503, "gateway restarting", null)
            val hub = newHub(repo)

            try {
                hub.resumeAttachToken(gatewayConfig(), AcpConnectionConfig.ResumeInfo("s1", "old-token"))
                fail("expected GatewayRequestException")
            } catch (expected: GatewayRequestException) {
                assertEquals(503, expected.statusCode)
            }
        }

    @Test
    fun `close deletes gateway session and releases its live transport`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo)
            val manager = hub.acquireChatManager()
            manager.forceConnectionState(AcpConnectionState.Connected)
            val chatId = hub.registerChat(sessionId = "s1", gatewaySessionId = "g1", manager = manager)
            hub.close(chatId, GatewayEndpoint("http", "gw", "cred"))
            assertEquals(listOf("g1"), repo.closed)
            assertFalse(hub.isTracked("srv", "s1"))
            assertEquals(0, hub.trackedCount())
            assertEquals(AcpConnectionState.Disconnected, manager.connectionState.value)
        }

    @Test
    fun `cold closeSession deletes the gateway session running the chat, even one already gone`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo)
            repo.sessions =
                listOf(GatewaySessionSummary("gw-dead", "r", "a", "disconnected", acpSessionIds = listOf("s1")))
            repo.closeFailure = GatewayRequestException(404, "gone", null)

            val closed = hub.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred"))

            assertTrue(closed)
            assertEquals(listOf("gw-dead"), repo.closed)
        }

    @Test
    fun `cold closeSession returns false when no gateway session runs the chat`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo)
            repo.sessions =
                listOf(GatewaySessionSummary("gw-1", "r", "a", "disconnected", acpSessionIds = listOf("other")))

            assertFalse(hub.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred")))
            assertTrue(repo.closed.isEmpty())
        }

    @Test
    fun `closeSession propagates non-404 gateway failures`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo)
            repo.sessions =
                listOf(GatewaySessionSummary("gw-1", "r", "a", "disconnected", acpSessionIds = listOf("s1")))
            repo.closeFailure = GatewayRequestException(500, "boom", null)

            try {
                hub.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred"))
                fail("expected GatewayRequestException")
            } catch (error: GatewayRequestException) {
                assertEquals(500, error.statusCode)
            }
        }

    @Test
    fun `closeSession on tracked chat succeeds when gateway reports 404`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo)
            hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")
            repo.closeFailure = GatewayRequestException(404, "gone", null)

            val closed = hub.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred"))

            assertTrue(closed)
            assertFalse(hub.isTracked("srv", "s1"))
        }

    @Test
    fun `hangUpBrowserTransport deletes the gateway session a failed attach left behind, once`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo)
            val transport = hub.createBrowserManager(CoroutineScope(SupervisorJob()))
            val unreachable = gatewayConfig().copy(endpoint = "ws://127.0.0.1:1/v1/acp/rt-1")
            withContext(
                UnconfinedTestDispatcher(testScheduler),
            ) { transport.connectAndInitializeWithoutReconnect(unreachable) }

            hub.hangUpBrowserTransport(transport)
            hub.hangUpBrowserTransport(transport)
            // The DELETE runs fire-and-forget on Dispatchers.IO; wait for it instead of
            // racing the assertion against the IO thread.
            runBlocking { withTimeout(5_000) { repo.awaitFirstClose() } }

            assertEquals(listOf("s1"), repo.closed)
        }

    /** A gateway transport config: the hub's resume lambda reads only its scheme/host/credential. */
    private fun gatewayConfig(): AcpConnectionConfig.Gateway =
        AcpConnectionConfig.Gateway(
            endpoint = "ws://gw/v1/acp/rt-1?sessionId=s1&attachToken=old-token",
            runtimeId = "rt-1",
            sourceId = "src-1",
            gatewayCredential = "cred",
            resume = AcpConnectionConfig.ResumeInfo("s1", "old-token"),
            scheme = "http",
            host = "gw",
            preferredAuthMethodId = null,
            displayName = null,
        )

    private fun fakeSnapshot() =
        com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot(
            loadState = com.tamimarafat.ferngeist.core.model.ChatLoadState.READY,
            messages = emptyList(),
            isStreaming = false,
            configOptions = emptyList(),
            availableCommands = emptyList(),
            commandsAdvertised = false,
            error = null,
            usage = null,
        )

    @Test
    fun `snapshot survives LRU eviction`() =
        runTest {
            val hub = newHub(maxHot = 3)
            val first = hub.registerChat(sessionId = "s1")
            hub.storeSnapshot(first, fakeSnapshot())
            hub.registerChat(sessionId = "s2")
            hub.registerChat(sessionId = "s3")
            hub.registerChat(sessionId = "s4")
            assertFalse(hub.isTracked("srv", "s1"))
            assertEquals(
                com.tamimarafat.ferngeist.core.model.ChatLoadState.READY,
                hub.snapshotFor(first)?.loadState,
            )
        }

    @Test
    fun `snapshot survives screen close but close drops it`() =
        runTest {
            val hub = newHub(FakeGatewayRepo())
            val id = hub.registerChat(sessionId = "s1", gatewaySessionId = "g1", manager = hub.acquireChatManager())
            hub.storeSnapshot(id, fakeSnapshot())
            hub.chatScreenOpened("srv", "s1", "/w")
            hub.chatScreenClosed("srv", "s1")
            assertTrue(hub.isTracked("srv", "s1"))
            assertEquals(
                com.tamimarafat.ferngeist.core.model.ChatLoadState.READY,
                hub.snapshotFor(id)?.loadState,
            )
            val id2 = hub.registerChat(sessionId = "s2", gatewaySessionId = "g2", manager = hub.acquireChatManager())
            hub.storeSnapshot(id2, fakeSnapshot())
            hub.close(id2, GatewayEndpoint("http", "gw", "cred"))
            assertNull(hub.snapshotFor(id2))
        }

    @Test
    fun `acquired manager stays tracked until abandon`() =
        runTest {
            val hub = newHub()
            val manager = hub.acquireChatManager()
            advanceUntilIdle()
            assertEquals(1, hub.trackedCount())
            assertFalse(hub.anyConnected.value)

            hub.abandon(manager)
            advanceUntilIdle()
            assertEquals(0, hub.trackedCount())
            assertFalse(hub.anyConnected.value)
        }

    @Test
    fun `register promotes acquired manager and abandon becomes a no-op`() =
        runTest {
            val hub = newHub()
            val manager = hub.acquireChatManager()
            hub.registerChat(sessionId = "s1", manager = manager)
            assertEquals(1, hub.trackedCount())

            hub.abandon(manager)
            advanceUntilIdle()
            assertEquals(1, hub.trackedCount())
            assertEquals("srv/s1", hub.managerFor("srv/s1")?.let { "srv/s1" })
        }

    @Test
    fun `eviction tears down the victim transport`() =
        runTest {
            val hub = newHub(maxHot = 3)
            val victims = listOf(hub.acquireChatManager(), hub.acquireChatManager(), hub.acquireChatManager())
            // Live before eviction, so Disconnected afterwards is the teardown's doing.
            victims[0].forceConnectionState(AcpConnectionState.Connected)
            hub.registerChat(sessionId = "a", manager = victims[0])
            hub.registerChat(sessionId = "b", manager = victims[1])
            hub.registerChat(sessionId = "c", manager = victims[2])
            assertEquals(3, hub.trackedCount())

            hub.registerChat(sessionId = "d", manager = hub.acquireChatManager())
            assertFalse(hub.isTracked("srv", "a"))
            assertNull(hub.managerFor("srv/a"))
            assertEquals(3, hub.trackedCount())
            assertEquals(AcpConnectionState.Disconnected, victims[0].connectionState.value)
        }

    @Test
    fun `browser manager releases with its owner scope`() =
        runTest {
            val hub = newHub()
            val ownerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            hub.createBrowserManager(ownerScope)
            advanceUntilIdle()
            assertEquals(1, hub.trackedCount())

            ownerScope.cancel()
            advanceUntilIdle()
            assertEquals(0, hub.trackedCount())
            assertFalse(hub.anyConnected.value)
        }

    @Test
    fun `aggregates stay false while all managers are disconnected`() =
        runTest {
            val hub = newHub()
            hub.registerChat(sessionId = "s1", manager = hub.acquireChatManager())
            hub.registerChat(sessionId = "s2", manager = hub.acquireChatManager())
            advanceUntilIdle()
            assertEquals(2, hub.trackedCount())
            assertFalse(hub.anyConnected.value)
            assertFalse(hub.anyActive.value)
        }

    @Test
    fun `aggregates still emit after all managers are gone`() =
        runTest {
            val hub = newHub()
            val first = hub.acquireChatManager()
            val second = hub.acquireChatManager()
            assertEquals(2, hub.trackedCount())

            hub.abandon(first)
            hub.abandon(second)
            advanceUntilIdle()
            assertEquals(0, hub.trackedCount())

            assertFalse(withTimeout(1_000L) { hub.anyConnected.first() })
            assertFalse(withTimeout(1_000L) { hub.anyActive.first() })
        }

    // ---- Presence: chatScreenOpened/Closed, tapTarget, onScreenChat,
    // warmServers, connectedSessionIds, warmManagerFor, isTracked ----

    @Test
    fun `same session id on two servers stays two entries`() =
        runTest {
            val hub = newHub()
            hub.registerChat(serverId = "srvA", sessionId = "s1")
            hub.registerChat(serverId = "srvB", sessionId = "s1")
            assertTrue(hub.isTracked("srvA", "s1"))
            assertTrue(hub.isTracked("srvB", "s1"))
            assertEquals(2, hub.trackedCount())
        }

    @Test
    fun `chatScreenOpened refuses the new session sentinel and stores nothing`() =
        runTest {
            val hub = newHub()
            assertFalse(hub.chatScreenOpened("srvA", NEW_SESSION_ARG, "/w"))
            assertFalse(hub.isTracked("srvA", NEW_SESSION_ARG))
            assertEquals(0, hub.trackedCount())
        }

    @Test
    fun `screen head follows open then close`() =
        runTest {
            val hub = newHub()
            hub.chatScreenOpened("srv", "a", "/a")
            hub.chatScreenOpened("srv", "b", "/b")
            assertEquals("b", hub.tapTarget.value?.sessionId)
            assertEquals("b", hub.onScreenChat.value?.sessionId)

            hub.chatScreenClosed("srv", "b")
            assertEquals("a", hub.tapTarget.value?.sessionId)
            assertEquals("a", hub.onScreenChat.value?.sessionId)
        }

    @Test
    fun `closing the screen of a connected chat keeps it tracked as the tap target`() =
        runTest {
            val hub = newHub()
            hub.registerChat(sessionId = "a", manager = hub.acquireChatManager())
            hub.chatScreenOpened("srv", "a", "/w")
            assertTrue(hub.isTracked("srv", "a"))

            hub.chatScreenClosed("srv", "a")
            assertTrue(hub.isTracked("srv", "a"))
            assertEquals("a", hub.tapTarget.value?.sessionId)
            assertNull(hub.onScreenChat.value)
        }

    @Test
    fun `screen-open chat beats a more recently focused pooled chat for the tap target`() =
        runTest {
            val hub = newHub()
            hub.registerChat(sessionId = "a", manager = hub.acquireChatManager())
            hub.chatScreenOpened("srv", "a", "/a")
            hub.registerChat(sessionId = "b", manager = hub.acquireChatManager())
            assertEquals("a", hub.tapTarget.value?.sessionId)
            assertEquals("a", hub.onScreenChat.value?.sessionId)
        }

    @Test
    fun `transport attach onto an existing entry never re-ranks the screen head`() =
        runTest {
            val hub = newHub()
            hub.chatScreenOpened("srv", "a", "/a")
            hub.registerChat(sessionId = "a", manager = hub.acquireChatManager())
            hub.chatScreenOpened("srv", "b", "/b")
            // Attaching the transport onto A again must NOT move A above B.
            hub.registerChat(sessionId = "a", manager = hub.acquireChatManager())
            assertEquals("b", hub.tapTarget.value?.sessionId)
            assertEquals("b", hub.onScreenChat.value?.sessionId)
        }

    @Test
    fun `eviction never removes a screen-open chat`() =
        runTest {
            val hub = newHub(maxHot = 3)
            hub.chatScreenOpened("srv", "a", "/a")
            hub.registerChat(sessionId = "b")
            hub.registerChat(sessionId = "c")
            hub.registerChat(sessionId = "d")
            assertTrue(hub.isTracked("srv", "a"))
            assertFalse(hub.isTracked("srv", "b"))
            assertTrue(hub.isTracked("srv", "c"))
            assertTrue(hub.isTracked("srv", "d"))
            assertEquals(3, hub.trackedCount())
        }

    @Test
    fun `warm presence tracks entries whose manager is actually connected only`() =
        runTest {
            val hub = newHub()
            // The register lambdas claim connected=true but the real managers
            // are Disconnected: presence must read the manager state, not the
            // lambdas, so nothing here is warm.
            hub.registerChat(serverId = "srvA", sessionId = "s1", manager = hub.acquireChatManager())
            hub.registerChat(serverId = "srvA", sessionId = "s2", manager = null)
            hub.registerChat(serverId = "srvB", sessionId = "s3", manager = hub.acquireChatManager())

            assertEquals(emptySet<String>(), hub.warmServers.value)
            assertEquals(emptySet<String>(), hub.connectedSessionIds("srvA").first())
            assertEquals(emptySet<String>(), hub.connectedSessionIds("srvB").first())
            assertNull(hub.warmManagerFor("srvA"))
            assertNull(hub.warmManagerFor("srvB"))
        }

    @Test
    fun `manager reaching connected updates warm presence without refresh`() =
        runTest {
            val hub = newHub()
            val manager = hub.acquireChatManager()
            hub.registerChat(serverId = "srvA", sessionId = "s1", manager = manager)
            advanceUntilIdle()
            assertEquals(emptySet<String>(), hub.warmServers.value)

            // No refresh() call anywhere in this test: warm presence must follow
            // the manager's own connection-state flow.
            hub.connectedSessionIds("srvA").test {
                assertEquals(emptySet<String>(), awaitItem())
                manager.forceConnectionState(AcpConnectionState.Connected)
                assertEquals(setOf("s1"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            advanceUntilIdle()
            assertEquals(setOf("srvA"), hub.warmServers.value)
        }

    @Test
    fun `connected chat keys isolate sessions and servers and follow disconnect without refresh`() =
        runTest {
            val hub = newHub()
            val first = hub.acquireChatManager()
            val second = hub.acquireChatManager()
            val other = hub.acquireChatManager()
            val disconnected = first.connectionState.value
            hub.registerChat(serverId = "srvA", sessionId = "s1", manager = first)
            hub.registerChat(serverId = "srvA", sessionId = "s2", manager = second)
            hub.registerChat(serverId = "srvB", sessionId = "s1", manager = other)
            advanceUntilIdle()
            assertEquals(emptySet<String>(), hub.connectedChatIds.value)

            first.forceConnectionState(AcpConnectionState.Connected)
            other.forceConnectionState(AcpConnectionState.Connected)
            advanceUntilIdle()
            assertEquals(setOf("srvA/s1", "srvB/s1"), hub.connectedChatIds.value)

            first.forceConnectionState(disconnected)
            advanceUntilIdle()
            assertEquals(setOf("srvB/s1"), hub.connectedChatIds.value)
        }

    @Test
    fun `queued disconnect observation keeps a newly registered connected chat streaming`() =
        runTest {
            val hub =
                ChatConnectionHub(
                    gatewayRepository = FakeGatewayRepo(),
                    scope = backgroundScope,
                    connectivityObserver = ConnectivityObserverStub(initialState = true),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            val first = hub.acquireChatManager()
            first.forceConnectionState(AcpConnectionState.Connected)
            hub.registerChat(sessionId = "first", manager = first)
            runCurrent()
            first.forceConnectionState(AcpConnectionState.Disconnected)
            backgroundScope.launch {
                val second = hub.acquireChatManager()
                second.forceConnectionState(AcpConnectionState.Connected)
                val key = hub.registerChat(sessionId = "second", manager = second)
                hub.storeSnapshot(key, fakeSnapshot().copy(isStreaming = true))
            }
            runCurrent()
            assertEquals(setOf("srv/second"), hub.streamingChatIds.value)
        }

    @Test
    fun `forced hot eviction clears streaming presence`() =
        runTest {
            val hub = newHub(maxHot = 1, maxGateway = 1)
            val first = hub.registerChat(sessionId = "first", streaming = true)
            hub.storeSnapshot(first, fakeSnapshot().copy(isStreaming = true))
            hub.registerChat(sessionId = "second")
            assertFalse(hub.isTracked("srv", "first"))
            assertFalse(first in hub.streamingChatIds.value)
        }

    @Test
    fun `snapshot cache eviction clears streaming presence`() =
        runTest {
            val hub = newHub()
            hub.storeSnapshot("srv/old", fakeSnapshot().copy(isStreaming = true))
            repeat(20) { hub.storeSnapshot("srv/$it", fakeSnapshot()) }
            assertNull(hub.snapshotFor("srv/old"))
            assertFalse("srv/old" in hub.streamingChatIds.value)
        }

    @Test
    fun `connection dropped mid turn clears streaming presence`() =
        runTest {
            val hub = newHub()
            val manager = hub.acquireChatManager()
            manager.forceConnectionState(AcpConnectionState.Connected)
            val key = hub.registerChat(sessionId = "s1", manager = manager)
            hub.storeSnapshot(key, fakeSnapshot().copy(isStreaming = true))
            assertTrue(key in hub.streamingChatIds.value)
            manager.forceConnectionState(AcpConnectionState.Disconnected)
            advanceUntilIdle()
            assertFalse(key in hub.streamingChatIds.value)
        }

    @Test
    fun `reconnecting a streaming bridge restores presence without a screen snapshot`() =
        runTest {
            val hub = newHub()
            val manager = hub.acquireChatManager()
            manager.forceConnectionState(AcpConnectionState.Connected)
            val bridge = SessionBridge("s1", null)
            bridge.runtime.beginHydration()
            bridge.runtime.completeHydration()
            bridge.sendPrompt("go", emptyList(), emptyList())
            val key =
                hub.register(
                    "srv",
                    "s1",
                    ChatRegistration(
                        gatewaySessionId = null,
                        gatewaySourceId = "",
                        agentId = "",
                        isConnected = { manager.isConnected },
                        isStreaming = { bridge.snapshot.value.isStreaming },
                        manager = manager,
                        snapshot = bridge.snapshot,
                    ),
                )
            assertTrue(key in hub.streamingChatIds.value)
            manager.forceConnectionState(AcpConnectionState.Disconnected)
            assertFalse(key in hub.streamingChatIds.value)
            manager.forceConnectionState(AcpConnectionState.Connected)
            assertTrue(key in hub.streamingChatIds.value)
            hub.close(key)
            bridge.close()
        }

    /**
     * Drives a connection's live connection state the way a successful transport
     * connect would. Unit tests cannot run a real ACP server, so this flips the
     * same private StateFlow the transport updates — observers watching
     * [AcpConnection.connectionState] cannot tell the difference.
     */
    private fun AcpConnection.forceConnectionState(state: AcpConnectionState) {
        val stateField = AcpConnection::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(this) as MutableStateFlow<AcpConnectionState>
        stateFlow.value = state
    }
}
