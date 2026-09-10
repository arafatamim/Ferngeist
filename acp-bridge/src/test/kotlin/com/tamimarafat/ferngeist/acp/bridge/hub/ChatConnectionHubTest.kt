package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.gateway.GatewaySessionRepository
import com.tamimarafat.ferngeist.gateway.GatewaySessionResumeResponse
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConnectionHubTest {
    private class FakeGatewayRepo : GatewaySessionRepository {
        val closed = mutableListOf<String>()
        var sessions: List<GatewaySessionSummary> = emptyList()

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
    }

    // Deterministic clock: each focus/register advances one tick.
    private var ticks = 0L
    private val clock: () -> Long = { ++ticks }

    private val evicted = mutableListOf<String>()

    private suspend fun ChatConnectionHub.registerChat(
        serverId: String = "srv",
        sessionId: String,
        gatewaySessionId: String? = "gs-$sessionId",
        gatewaySourceId: String = "src-1",
        agentId: String = "agent-1",
        connected: Boolean = true,
        streaming: Boolean = false,
    ): String {
        val id = sessionId
        return register(
            serverId = serverId,
            sessionId = sessionId,
            gatewaySessionId = gatewaySessionId,
            gatewaySourceId = gatewaySourceId,
            agentId = agentId,
            isConnected = { connected },
            isStreaming = { streaming },
            onEvict = { evicted += id },
        )
    }

    @Test
    fun `register returns stable chatIds for same server and session`() =
        runTest {
            val hub = ChatConnectionHub(gatewayRepository = null, clock = clock)
            val first = hub.registerChat(sessionId = "s1")
            val second = hub.registerChat(sessionId = "s1", gatewaySessionId = null)
            assertEquals(first, second)
            assertEquals(1, hub.liveChats.value.size)
        }

    @Test
    fun `unregister drops tracking without running onEvict`() =
        runTest {
            val hub = ChatConnectionHub(gatewayRepository = null, clock = clock)
            val chatId = hub.registerChat(sessionId = "s1")
            hub.unregister(chatId)
            assertTrue(hub.liveChats.value.isEmpty())
            assertFalse(evicted.contains("s1"))
        }

    @Test
    fun `fourth registration evicts the least recently focused idle entry`() =
        runTest {
            val hub = ChatConnectionHub(gatewayRepository = null, clock = clock)
            hub.registerChat(sessionId = "a")
            hub.registerChat(sessionId = "b")
            hub.registerChat(sessionId = "c")
            hub.registerChat(sessionId = "d")
            assertTrue(evicted.contains("a"))
            assertNull(hub.liveChats.value.firstOrNull { it.sessionId == "a" })
            assertEquals(3, hub.liveChats.value.size)
        }

    @Test
    fun `streaming entries are never evicted even over cap`() =
        runTest {
            val hub = ChatConnectionHub(gatewayRepository = null, clock = clock)
            hub.registerChat(sessionId = "a", streaming = true)
            hub.registerChat(sessionId = "b", streaming = true)
            hub.registerChat(sessionId = "c", streaming = true)
            hub.registerChat(sessionId = "d")
            // d itself is the only idle candidate; nothing else may be evicted
            assertEquals(4, hub.liveChats.value.size)
            assertTrue(hub.liveChats.value.all { it.sessionId != "a" || it.streaming })
        }

    @Test
    fun `mixed cap eviction skips streaming and takes oldest idle`() =
        runTest {
            val hub = ChatConnectionHub(gatewayRepository = null, clock = clock)
            hub.registerChat(sessionId = "a") // idle, oldest
            hub.registerChat(sessionId = "b", streaming = true)
            hub.registerChat(sessionId = "c", streaming = true)
            hub.registerChat(sessionId = "d")
            assertTrue(evicted.contains("a"))
            assertFalse(evicted.contains("b"))
            assertFalse(evicted.contains("c"))
            assertEquals(
                setOf("b", "c", "d"),
                hub.liveChats.value
                    .map { it.sessionId }
                    .toSet(),
            )
        }

    @Test
    fun `focus reorders recency so focused entry survives eviction`() =
        runTest {
            val hub = ChatConnectionHub(gatewayRepository = null, clock = clock)
            hub.registerChat(sessionId = "a")
            hub.registerChat(sessionId = "b")
            hub.registerChat(sessionId = "c")
            hub.focus(
                hub.liveChats.value
                    .first { it.sessionId == "a" }
                    .chatId,
            )
            hub.registerChat(sessionId = "d")
            assertFalse(evicted.contains("a"))
            assertTrue(evicted.contains("b"))
        }

    @Test
    fun `hasLiveGatewaySession requires matching source agent and connection`() =
        runTest {
            val hub = ChatConnectionHub(gatewayRepository = null, clock = clock)
            hub.registerChat(sessionId = "live", gatewaySourceId = "src-1", agentId = "agent-1", connected = true)
            hub.registerChat(sessionId = "dead", gatewaySourceId = "src-1", agentId = "agent-1", connected = false)
            assertTrue(hub.hasLiveGatewaySession("src-1", "agent-1"))
            assertFalse(hub.hasLiveGatewaySession("src-2", "agent-1"))
            assertFalse(hub.hasLiveGatewaySession("src-1", "agent-2"))
        }

    @Test
    fun `close deletes gateway session then runs onEvict and unregisters`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = ChatConnectionHub(gatewayRepository = repo, clock = clock)
            val chatId = hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")
            hub.close(chatId, GatewayEndpoint("http", "gw", "cred"))
            assertEquals(listOf("g1"), repo.closed)
            assertTrue(evicted.contains("s1"))
            assertTrue(hub.liveChats.value.isEmpty())
        }

    @Test
    fun `capacity closes oldest unprotected active session when device cap reached`() =
        runTest {
            val repo = FakeGatewayRepo()
            val hub = ChatConnectionHub(gatewayRepository = repo, maxGatewaySessionsPerDevice = 5, clock = clock)
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
            val hub =
                ChatConnectionHub(
                    gatewayRepository = repo,
                    maxHotConnections = 10,
                    maxGatewaySessionsPerDevice = 5,
                    clock = clock,
                )
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
}
