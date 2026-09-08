package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewaySessionResumeResponse
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConnectionHubTest {
    private class FakeGatewayRepo : GatewayRepository {
        val closed = mutableListOf<String>()
        var sessions: List<GatewaySessionSummary> = emptyList()

        override suspend fun fetchStatus(
            scheme: String,
            host: String,
        ) = TODO("unused in hub tests")

        override suspend fun startPairing(
            scheme: String,
            host: String,
        ) = TODO("unused in hub tests")

        override suspend fun getPairingStatus(
            scheme: String,
            host: String,
            challengeId: String,
        ) = TODO("unused in hub tests")

        override suspend fun completePairing(
            scheme: String,
            host: String,
            challengeId: String,
            code: String,
            deviceName: String,
        ) = TODO("unused in hub tests")

        override suspend fun refreshCredential(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO("unused in hub tests")

        override suspend fun fetchAgents(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO("unused in hub tests")

        override suspend fun startAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ) = TODO("unused in hub tests")

        override suspend fun connectRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            sessionMode: String?,
            fresh: Boolean,
        ) = TODO("unused in hub tests")

        override suspend fun restartRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            envVars: Map<String, String>,
        ) = TODO("unused in hub tests")

        override suspend fun fetchRuntimeLogs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ) = TODO("unused in hub tests")

        override suspend fun resumeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ): GatewaySessionResumeResponse = TODO("unused in hub tests")

        override suspend fun listGatewaySessions(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): List<GatewaySessionSummary> = sessions

        override suspend fun closeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) {
            closed += sessionId
        }

        override suspend fun registerPushToken(
            scheme: String,
            host: String,
            gatewayCredential: String,
            token: String,
            platform: String,
        ) = TODO("unused in hub tests")

        override suspend fun fetchWorkspaceFile(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String,
        ) = TODO("unused in hub tests")

        override suspend fun fetchGitStatus(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ) = TODO("unused in hub tests")

        override suspend fun fetchGitDiff(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String?,
        ) = TODO("unused in hub tests")
    }

    // Deterministic clock: each focus/register advances one tick.
    private var ticks = 0L
    private val clock: () -> Long = { ++ticks }

    private fun newHub(
        repo: GatewayRepository? = null,
        maxHot: Int = 3,
        maxGateway: Int = 5,
    ): ChatConnectionHub =
        ChatConnectionHub(
            gatewayRepository = repo,
            scope = CoroutineScope(Dispatchers.Unconfined),
            connectivityObserver = ConnectivityObserverStub(initialState = true),
            maxHotConnections = maxHot,
            maxGatewaySessionsPerDevice = maxGateway,
            clock = clock,
        )

    private suspend fun ChatConnectionHub.registerChat(
        serverId: String = "srv",
        sessionId: String,
        gatewaySessionId: String? = "gs-$sessionId",
        gatewaySourceId: String = "src-1",
        agentId: String = "agent-1",
        connected: Boolean = true,
        streaming: Boolean = false,
        manager: AcpConnectionManager? = null,
    ): String =
        register(
            serverId = serverId,
            sessionId = sessionId,
            gatewaySessionId = gatewaySessionId,
            gatewaySourceId = gatewaySourceId,
            agentId = agentId,
            isConnected = { connected },
            isStreaming = { streaming },
            manager = manager,
        )

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
    fun `hasLiveGatewaySession requires matching source agent and connection`() =
        runTest {
            val hub = newHub()
            hub.registerChat(sessionId = "live", gatewaySourceId = "src-1", agentId = "agent-1", connected = true)
            hub.registerChat(sessionId = "dead", gatewaySourceId = "src-1", agentId = "agent-1", connected = false)
            assertTrue(hub.hasLiveGatewaySession("src-1", "agent-1"))
            assertFalse(hub.hasLiveGatewaySession("src-2", "agent-1"))
            assertFalse(hub.hasLiveGatewaySession("src-1", "agent-2"))
        }

    @Test
    fun `close deletes gateway session and drops tracking`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo)
            val chatId = hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")
            hub.close(chatId, GatewayEndpoint("http", "gw", "cred"))
            assertEquals(listOf("g1"), repo.closed)
            assertFalse(hub.isTracked("srv", "s1"))
            assertEquals(0, hub.trackedCount())
        }

    @Test
    fun `capacity closes oldest unprotected active session when device cap reached`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo, maxGateway = 5)
            hub.registerChat(serverId = "srv", sessionId = "mine", gatewaySessionId = "g-mine")
            repo.sessions = (1..5).map { i ->
                GatewaySessionSummary(
                    sessionId = "g$i",
                    runtimeId = "r$i",
                    agentId = "agent-$i",
                    status = "active",
                    createdAt = "2026-01-0${i + 1}T00:00:00Z",
                )
            } + GatewaySessionSummary("g-mine", "r-mine", "agent-1", "active", "2026-01-06T00:00:00Z")
            hub.ensureGatewayCapacity(GatewayEndpoint("http", "gw", "cred"))
            // g1..g5 are freeable; g-mine is protected by a tracked entry.
            // Oldest freeable by createdAt is g1.
            assertEquals(listOf("g1"), repo.closed)
        }

    @Test
    fun `capacity throws when every active session is protected`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo, maxHot = 10, maxGateway = 5)
            repeat(5) { i -> hub.registerChat(sessionId = "s$i", gatewaySessionId = "g$i") }
            repo.sessions =
                (0 until 5).map { i ->
                    GatewaySessionSummary("g$i", "r$i", "agent-1", "active", "2026-01-0${i + 1}T00:00:00Z")
                }
            try {
                hub.ensureGatewayCapacity(GatewayEndpoint("http", "gw", "cred"))
                throw AssertionError("expected IllegalStateException")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("busy"))
            }
            assertTrue(repo.closed.isEmpty())
        }

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
    fun `eviction tears down the victim transport exactly once`() =
        runTest {
            val hub = newHub(maxHot = 3)
            val victims = listOf(hub.acquireChatManager(), hub.acquireChatManager(), hub.acquireChatManager())
            hub.registerChat(sessionId = "a", manager = victims[0])
            hub.registerChat(sessionId = "b", manager = victims[1])
            hub.registerChat(sessionId = "c", manager = victims[2])
            assertEquals(3, hub.trackedCount())

            hub.registerChat(sessionId = "d", manager = hub.acquireChatManager())
            assertFalse(hub.isTracked("srv", "a"))
            assertNull(hub.managerFor("srv/a"))
            assertEquals(3, hub.trackedCount())
        }

    @Test
    fun `close tears down the entry transport`() =
        runTest {
            val hub = newHub(FakeGatewayRepo())
            val manager = hub.acquireChatManager()
            val chatId = hub.registerChat(sessionId = "s1", gatewaySessionId = "g1", manager = manager)
            assertEquals(1, hub.trackedCount())

            hub.close(chatId, GatewayEndpoint("http", "gw", "cred"))
            assertFalse(hub.isTracked("srv", "s1"))
            assertEquals(0, hub.trackedCount())
        }

    @Test
    fun `browser manager releases with its owner scope`() =
        runTest {
            val hub = newHub()
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
            hub.registerChat(serverId = "srvA", sessionId = "s1", connected = true, manager = hub.acquireChatManager())
            hub.registerChat(serverId = "srvA", sessionId = "s2", manager = null)
            hub.registerChat(serverId = "srvB", sessionId = "s3", connected = true, manager = hub.acquireChatManager())

            assertEquals(emptySet<String>(), hub.warmServers.value)
            assertEquals(emptySet<String>(), hub.connectedSessionIds("srvA").first())
            assertEquals(emptySet<String>(), hub.connectedSessionIds("srvB").first())
            assertNull(hub.warmManagerFor("srvA"))
            assertNull(hub.warmManagerFor("srvB"))
        }
}
