package com.tamimarafat.ferngeist.acp.bridge.catalog

import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.FakeAuthEnvValueStore
import com.tamimarafat.ferngeist.acp.bridge.hub.FakeGatewayRepo
import com.tamimarafat.ferngeist.acp.bridge.hub.FakeGatewaySources
import com.tamimarafat.ferngeist.acp.bridge.hub.FakeLaunchableTargets
import com.tamimarafat.ferngeist.acp.bridge.hub.FakeSessionRepo
import com.tamimarafat.ferngeist.acp.bridge.hub.newHub
import com.tamimarafat.ferngeist.acp.bridge.hub.registerChat
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session catalog's own behaviour: listing over the hub's transports, the
 * gateway REST legs (close, delete, lease reconcile) and the auth helpers the
 * session list drives.
 *
 * Listing legs that need a live socket are covered by the hub's transport tests;
 * what is asserted here is the catalog's own decisions — which transport it
 * borrows, which endpoint it resolves, and what it writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCatalogTest {
    /**
     * The hub defaults to one built on the same fakes: the close/delete legs run
     * through the hub (its own repository and session table), so a hub with
     * separate fakes would record into stores the test never inspects.
     */
    private fun newCatalog(
        repo: GatewayRepository = FakeGatewayRepo(),
        sessionRepository: ChatCatalog = FakeSessionRepo(),
        targets: FakeLaunchableTargets = FakeLaunchableTargets(),
        authEnvValueStore: AuthEnvValueStore = FakeAuthEnvValueStore(),
        hub: ChatConnectionHub = newHub(repo = repo),
    ): SessionCatalog {
        val dispatcher = UnconfinedTestDispatcher()
        return SessionCatalog(
            hub = hub,
            repositories =
                LaunchRepositories(
                    gatewayRepository = repo,
                    gatewaySourceRepository = FakeGatewaySources(),
                    launchableTargetRepository = targets,
                ),
            sessionRepository = sessionRepository,
            authEnvValueStore = authEnvValueStore,
            scope = CoroutineScope(dispatcher),
            ioDispatcher = dispatcher,
        )
    }

    /** A gateway target whose source carries the endpoint triple the REST legs must use. */
    private fun gatewayTarget(): LaunchableTarget.GatewayAgent =
        LaunchableTarget.GatewayAgent(
            binding = GatewayAgentBinding(id = "srv", name = "srv", gatewaySourceId = "src", agentId = "agent-1"),
            gatewaySource =
                GatewaySource(
                    id = "src",
                    name = "gw",
                    scheme = "http",
                    host = "gw",
                    gatewayCredential = "cred",
                ),
        )

    @Test
    fun `listSessions reports a removed target without touching the session table`() =
        runTest {
            val sessions = FakeSessionRepo()
            val catalog = newCatalog(sessionRepository = sessions)

            val result = catalog.listSessions("srv", cwd = null)

            assertTrue(result is SessionCatalog.ListSessionsResult.Failed)
            assertEquals(0, sessions.replaceCount)
        }

    @Test
    fun `adoptListing on a transport that cannot list records nothing for reuse`() =
        runTest {
            val sessions = FakeSessionRepo()
            val hub = newHub()
            val transport = hub.createBrowserManager(CoroutineScope(UnconfinedTestDispatcher()))
            val catalog = newCatalog(sessionRepository = sessions, hub = hub)

            catalog.adoptListing("srv", transport)
            val result = catalog.listSessions("srv", cwd = null, reuseRecent = true)

            // A failed adopt must not stand in for a listing: the call falls through to a real one.
            assertEquals(0, sessions.replaceCount)
            assertTrue(result is SessionCatalog.ListSessionsResult.Failed)
        }

    @Test
    fun `authenticateListing fails when no transport raised a challenge`() =
        runTest {
            val catalog = newCatalog()

            val result = catalog.authenticateListing("srv", methodId = "m1")

            assertTrue(result is SessionCatalog.ListingAuthentication.Failed)
        }

    @Test
    fun `closeSession closes the gateway session running the chat on the target's endpoint`() =
        runTest {
            val repo = FakeGatewayRepo()
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val catalog = newCatalog(repo = repo, targets = targets)
            repo.sessions =
                listOf(GatewaySessionSummary("gw-1", "r-1", "agent-1", "disconnected", acpSessionIds = listOf("s1")))

            val closed = catalog.closeSession("srv", "s1")

            assertTrue(closed)
            assertEquals(listOf("gw-1"), repo.closed)
        }

    @Test
    fun `closeSession returns false when the target has no gateway leg`() =
        runTest {
            val repo = FakeGatewayRepo()
            val catalog = newCatalog(repo = repo)
            repo.sessions =
                listOf(GatewaySessionSummary("gw-1", "r-1", "agent-1", "disconnected", acpSessionIds = listOf("s1")))

            assertFalse(catalog.closeSession("srv", "s1"))
            assertTrue(repo.closed.isEmpty())
        }

    @Test
    fun `deleteSession keeps the chat when its agent is no longer available`() =
        runTest {
            val sessions = FakeSessionRepo()
            val catalog = newCatalog(sessionRepository = sessions)
            sessions.chatCreated("srv", SessionSummary(id = "s1"))

            val result = runCatching { catalog.deleteSession("srv", "s1") }

            assertTrue(result.isFailure)
            assertEquals("s1", sessions.getSession("srv", "s1")?.id)
        }

    @Test
    fun `deleteSession keeps row and tracking when gateway launch fails`() =
        runTest {
            val repo = FakeGatewayRepo()
            // An incompatible gateway cannot confirm deletion; retain both row and presence.
            repo.protocolVersion = "v1"
            val sessions = FakeSessionRepo()
            val hub = newHub(repo = repo)
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val catalog = newCatalog(hub = hub, repo = repo, sessionRepository = sessions, targets = targets)
            sessions.chatCreated("srv", SessionSummary(id = "s1"))
            hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")

            val result = runCatching { catalog.deleteSession("srv", "s1") }

            assertTrue(result.isFailure)
            assertTrue(repo.closed.isEmpty())
            assertEquals("s1", sessions.getSession("srv", "s1")?.id)
            assertTrue(hub.isTracked("srv", "s1"))
        }

    @Test
    fun `deleteSession retains tracked direct chat when deletion cannot reach its agent`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo = repo)
            val catalog = newCatalog(hub = hub, repo = repo, sessionRepository = sessions)
            sessions.chatCreated("srv", SessionSummary(id = "s1"))
            hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")

            val result = runCatching { catalog.deleteSession("srv", "s1") }

            assertTrue(result.isFailure)
            assertTrue(repo.closed.isEmpty())
            assertEquals("s1", sessions.getSession("srv", "s1")?.id)
            assertTrue(hub.isTracked("srv", "s1"))
        }

    @Test
    fun `deleteSession on an unknown session returns false without throwing`() =
        runTest {
            val catalog = newCatalog()

            assertFalse(catalog.deleteSession("srv", "ghost"))
        }

    @Test
    fun `runningSessionIds lists the chats the gateway runs for this agent only`() =
        runTest {
            val repo = FakeGatewayRepo()
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val catalog = newCatalog(repo = repo, targets = targets)
            repo.sessions =
                listOf(
                    GatewaySessionSummary("g-1", "r-1", "agent-1", "active", acpSessionIds = listOf("s1", "s2")),
                    GatewaySessionSummary("g-2", "r-2", "agent-other", "active", acpSessionIds = listOf("s3")),
                )

            assertEquals(setOf("s1", "s2"), catalog.runningSessionIds("srv"))
        }

    @Test
    fun `runningSessionIds is unknown when the gateway list fails`() =
        runTest {
            val repo = FakeGatewayRepo()
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val catalog = newCatalog(repo = repo, targets = targets)
            repo.listFailure = GatewayRequestException(500, "boom", null)

            assertNull(catalog.runningSessionIds("srv"))
        }

    @Test
    fun `envPayload keeps declared values and drops blank optional ones`() =
        runTest {
            val catalog = newCatalog()
            val method =
                CatalogAuthMethod(
                    id = "m1",
                    name = "Env",
                    description = null,
                    type = "env",
                    envVars =
                        listOf(
                            CatalogAuthEnvVar("TOKEN", "Token", secret = true, optional = false),
                            CatalogAuthEnvVar("OPT", "Optional", secret = false, optional = true),
                        ),
                )

            val payload = catalog.envPayload(method, mapOf("TOKEN" to " secret ", "OPT" to "  ", "OTHER" to "x"))

            assertEquals(mapOf("TOKEN" to "secret"), payload)
        }

    @Test
    fun `persistedEnvValues round-trips only the names the methods declare`() =
        runTest {
            val store = FakeAuthEnvValueStore()
            val catalog = newCatalog(authEnvValueStore = store)
            val method =
                CatalogAuthMethod(
                    id = "m1",
                    name = "Env",
                    description = null,
                    type = "env",
                    envVars = listOf(CatalogAuthEnvVar("TOKEN", "Token", secret = true, optional = false)),
                )
            val other =
                CatalogAuthMethod(
                    id = "m2",
                    name = "Other",
                    description = null,
                    type = "env",
                    envVars = listOf(CatalogAuthEnvVar("KEY", "Key", secret = true, optional = false)),
                )

            catalog.persistEnvValues("srv", method, mapOf("TOKEN" to "t", "KEY" to "k"))
            catalog.persistEnvValues("srv", other, mapOf("KEY" to "k"))

            assertEquals(mapOf("TOKEN" to "t"), catalog.persistedEnvValues("srv", listOf(method)))
            assertEquals(mapOf("KEY" to "k"), catalog.persistedEnvValues("srv", listOf(other)))
        }
}
