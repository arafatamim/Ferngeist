package com.tamimarafat.ferngeist.acp.bridge.hub

import app.cash.turbine.test
import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.GatewaySessionResumeResponse
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConnectionHubTest {
    private class FakeGatewayRepo : GatewayRepository {
        val closed = mutableListOf<String>()
        var sessions: List<GatewaySessionSummary> = emptyList()
        var closeFailure: Exception? = null
        var listFailure: Exception? = null

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
            new: Boolean,
        ) = TODO("unused in hub tests")

        override suspend fun connectRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            sessionMode: String?,
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
        ): List<GatewaySessionSummary> {
            listFailure?.let { throw it }
            return sessions
        }

        override suspend fun closeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) {
            closed += sessionId
            closeFailure?.let { throw it }
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

        override suspend fun createCustomAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            displayName: String,
            command: String,
            args: List<String>,
            hint: String,
        ): GatewayAgent = TODO("unused in hub tests")

        override suspend fun deleteCustomAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ) = TODO("unused in hub tests")

        override suspend fun stopAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ) = TODO("unused in hub tests")
    }

    // Deterministic clock: each focus/register advances one tick.
    private var ticks = 0L
    private val clock: () -> Long = { ++ticks }

    private class FakeSessionRepo : SessionRepository {
        private val rows = linkedMapOf<String, SessionSummary>()

        /** Title writes, so read-only paths can assert they wrote nothing. */
        var titleWrites = 0
            private set

        /** Whole-table replacements, so read-only paths can assert they wrote nothing. */
        var replaceCount = 0
            private set

        private fun key(
            serverId: String,
            sessionId: String,
        ) = "$serverId/$sessionId"

        override fun getSessions(serverId: String): Flow<List<SessionSummary>> =
            flowOf(rows.filterKeys { it.startsWith("$serverId/") }.values.toList())

        override fun getRecentSessions(limit: Int): Flow<List<SessionSummary>> =
            flowOf(rows.values.sortedByDescending { it.updatedAt }.take(limit))

        override suspend fun getSession(
            serverId: String,
            sessionId: String,
        ): SessionSummary? = rows[key(serverId, sessionId)]

        override suspend fun upsertSession(
            serverId: String,
            summary: SessionSummary,
        ) {
            rows[key(serverId, summary.id)] = summary.copy(serverId = serverId)
        }

        override suspend fun updateSessionTitle(
            serverId: String,
            sessionId: String,
            title: String,
        ) {
            titleWrites++
            rows[key(serverId, sessionId)]?.let { rows[key(serverId, sessionId)] = it.copy(title = title) }
        }

        override suspend fun setGatewaySessionId(
            serverId: String,
            sessionId: String,
            gatewaySessionId: String?,
        ) {
            rows[key(serverId, sessionId)]?.let {
                rows[key(serverId, sessionId)] = it.copy(gatewaySessionId = gatewaySessionId)
            }
        }

        override suspend fun deleteSession(
            serverId: String,
            sessionId: String,
        ) {
            rows.remove(key(serverId, sessionId))
        }

        override suspend fun clearStaleGatewaySessions(
            serverId: String,
            liveGatewaySessionIds: Set<String>,
        ): Int {
            val stale =
                rows.keys.filter { key ->
                    key.startsWith("$serverId/") &&
                        rows[key]?.gatewaySessionId.let { id -> id != null && id !in liveGatewaySessionIds }
                }
            stale.forEach { rows[it] = rows.getValue(it).copy(gatewaySessionId = null) }
            return stale.size
        }

        override suspend fun clearSessions(serverId: String) {
            rows.keys.removeAll { it.startsWith("$serverId/") }
        }

        override suspend fun replaceSessions(
            serverId: String,
            sessions: List<SessionSummary>,
        ) {
            replaceCount++
            clearSessions(serverId)
            sessions.forEach { upsertSession(serverId, it) }
        }
    }

    private fun newHub(
        repo: GatewayRepository? = null,
        maxHot: Int = 3,
        maxGateway: Int = 5,
        sessionRepository: SessionRepository? = null,
    ): ChatConnectionHub =
        ChatConnectionHub(
            gatewayRepository = repo,
            scope = CoroutineScope(Dispatchers.Unconfined),
            connectivityObserver = ConnectivityObserverStub(initialState = true),
            maxHotConnections = maxHot,
            maxGatewaySessionsPerDevice = maxGateway,
            clock = clock,
            sessionRepository = sessionRepository,
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
    fun `resolveSessionTitle is read-only and null while no transport is warm`() =
        runTest {
            val sessionRepo = FakeSessionRepo()
            val hub = newHub(sessionRepository = sessionRepo)

            val title = hub.resolveSessionTitle(serverId = "srv", sessionId = "s1", cwd = "/w")

            assertNull(title)
            assertEquals(0, sessionRepo.replaceCount)
            assertEquals(0, sessionRepo.titleWrites)
        }

    @Test
    fun `resolveSessionTitle swallows a listing failure instead of raising it into the chat`() =
        runTest {
            val sessionRepo = FakeSessionRepo()
            val hub = newHub(sessionRepository = sessionRepo)
            // Warm transport that is registered as connected but holds no SDK client,
            // so the listing call fails the way a half-open socket does.
            hub.registerChat(sessionId = "s1", manager = hub.acquireChatManager())

            val title = hub.resolveSessionTitle(serverId = "srv", sessionId = "s1", cwd = "/w")

            assertNull(title)
            assertEquals(0, sessionRepo.replaceCount)
            assertEquals(0, sessionRepo.titleWrites)
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
    fun `at cap an idle entry is evicted and the new registration lands`() =
        runTest {
            val hub = newHub(maxHot = 3, maxGateway = 5)
            hub.registerChat(sessionId = "a")
            hub.registerChat(sessionId = "b")
            hub.registerChat(sessionId = "c")
            hub.registerChat(sessionId = "d")
            assertFalse(hub.isTracked("srv", "a"))
            assertTrue(hub.isTracked("srv", "d"))
            assertEquals(3, hub.trackedCount())
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
    fun `on-screen entry is kept over a less recently focused idle pooled entry`() =
        runTest {
            val hub = newHub(maxHot = 3, maxGateway = 5)
            hub.chatScreenOpened("srv", "a", "/a") // oldest, but on screen
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
    fun `deleteSession removes local row and reports known for a cold session`() =
        runTest {
            val sessions = FakeSessionRepo()
            val hub = newHub(sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1"))

            val deleted = hub.deleteSession("srv", "s1", endpoint = null)

            assertTrue(deleted)
            assertNull(sessions.getSession("srv", "s1"))
        }

    @Test
    fun `deleteSession closes the gateway session then drops row and tracking`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1"))
            hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")

            val deleted =
                hub.deleteSession("srv", "s1", GatewayEndpoint("http", "gw", "cred"))

            assertTrue(deleted)
            assertEquals(listOf("g1"), repo.closed)
            assertNull(sessions.getSession("srv", "s1"))
            assertFalse(hub.isTracked("srv", "s1"))
        }

    @Test
    fun `deleteSession without endpoint tears down a tracked chat with no REST call`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1"))
            hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")

            val deleted = hub.deleteSession("srv", "s1", endpoint = null)

            assertTrue(deleted)
            assertTrue(repo.closed.isEmpty())
            assertNull(sessions.getSession("srv", "s1"))
            assertFalse(hub.isTracked("srv", "s1"))
        }

    @Test
    fun `deleteSession on an unknown session returns false without throwing`() =
        runTest {
            val sessions = FakeSessionRepo()
            val hub = newHub(sessionRepository = sessions)

            assertFalse(hub.deleteSession("srv", "ghost", endpoint = null))
        }

    @Test
    fun `closeSession clears stale pointer when gateway reports 404`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1", gatewaySessionId = "gw-dead"))
            repo.closeFailure = GatewayRequestException(404, "gone", null)

            val closed = hub.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred"))

            assertTrue(closed)
            assertEquals(listOf("gw-dead"), repo.closed)
            assertNull(sessions.getSession("srv", "s1")?.gatewaySessionId)
        }

    @Test
    fun `closeSession keeps pointer and propagates non-404 gateway failures`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1", gatewaySessionId = "gw-1"))
            repo.closeFailure = GatewayRequestException(500, "boom", null)

            try {
                hub.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred"))
                fail("expected GatewayRequestException")
            } catch (error: GatewayRequestException) {
                assertEquals(500, error.statusCode)
            }
            assertEquals("gw-1", sessions.getSession("srv", "s1")?.gatewaySessionId)
        }

    @Test
    fun `closeSession on tracked chat clears pointer when gateway reports 404`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1", gatewaySessionId = "g1"))
            hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")
            repo.closeFailure = GatewayRequestException(404, "gone", null)

            val closed = hub.closeSession("srv", "s1", GatewayEndpoint("http", "gw", "cred"))

            assertTrue(closed)
            assertFalse(hub.isTracked("srv", "s1"))
            assertNull(sessions.getSession("srv", "s1")?.gatewaySessionId)
        }

    @Test
    fun `reconcileGatewaySessions clears stale pointers and keeps live ones`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "alive", gatewaySessionId = "g-live"))
            sessions.upsertSession("srv", SessionSummary(id = "dead", gatewaySessionId = "g-dead"))
            sessions.upsertSession("srv", SessionSummary(id = "plain"))
            repo.sessions =
                listOf(GatewaySessionSummary("g-live", "r-live", "agent-1", "active", "2026-09-22T00:00:00Z"))

            hub.reconcileGatewaySessions("srv", GatewayEndpoint("http", "gw", "cred"))

            assertEquals("g-live", sessions.getSession("srv", "alive")?.gatewaySessionId)
            assertNull(sessions.getSession("srv", "dead")?.gatewaySessionId)
            assertNull(sessions.getSession("srv", "plain")?.gatewaySessionId)
        }

    @Test
    fun `reconcileGatewaySessions keeps pointers when the gateway list fails`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1", gatewaySessionId = "g-1"))
            repo.listFailure = GatewayRequestException(500, "boom", null)

            hub.reconcileGatewaySessions("srv", GatewayEndpoint("http", "gw", "cred"))

            assertEquals("g-1", sessions.getSession("srv", "s1")?.gatewaySessionId)
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
    fun `capacity counts disconnected sessions the gateway still leases`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo, maxGateway = 5)
            // The gateway counts a disconnected session against MaxPerDevice — its
            // runtime lease is still held (lifecycle.go: StatusActive||StatusDisconnected).
            // Counting only `active` here reports a free slot the gateway does not
            // have, so the spawn that follows comes back session-less.
            repo.sessions =
                (1..5).map { i ->
                    GatewaySessionSummary(
                        sessionId = "g$i",
                        runtimeId = "r$i",
                        agentId = "agent-$i",
                        status = "disconnected",
                        createdAt = "2026-01-0${i + 1}T00:00:00Z",
                    )
                }
            hub.ensureGatewayCapacity(GatewayEndpoint("http", "gw", "cred"))
            assertEquals(listOf("g1"), repo.closed)
        }

    @Test
    fun `capacity ignores sessions whose runtime lease is dead`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = newHub(repo, maxGateway = 5)
            // `failed` holds no lease, so these occupy no slot and there is
            // nothing to free — the helper must leave them alone.
            repo.sessions =
                (1..5).map { i ->
                    GatewaySessionSummary(
                        sessionId = "g$i",
                        runtimeId = "r$i",
                        agentId = "agent-$i",
                        status = "failed",
                        createdAt = "2026-01-0${i + 1}T00:00:00Z",
                    )
                }
            hub.ensureGatewayCapacity(GatewayEndpoint("http", "gw", "cred"))
            assertTrue(repo.closed.isEmpty())
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

    /**
     * Drives a manager's live connection state the way a successful transport
     * connect would. Unit tests cannot run a real ACP server, so this flips the
     * same private StateFlow the transport updates — observers watching
     * [AcpConnectionManager.connectionState] cannot tell the difference.
     */
    private fun AcpConnectionManager.forceConnectionState(state: AcpConnectionState) {
        val managerClass = AcpConnectionManager::class.java
        val orchestraField = managerClass.getDeclaredField("orchestra")
        orchestraField.isAccessible = true
        val orchestra = orchestraField.get(this)
        val stateField = orchestra.javaClass.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(orchestra) as MutableStateFlow<AcpConnectionState>
        stateFlow.value = state
    }
}
