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
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
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
        sessionRepository: SessionRepository = FakeSessionRepo(),
        targets: FakeLaunchableTargets = FakeLaunchableTargets(),
        authEnvValueStore: AuthEnvValueStore = FakeAuthEnvValueStore(),
        hub: ChatConnectionHub = newHub(repo = repo, sessionRepository = sessionRepository),
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
            val hub = newHub(sessionRepository = sessions)
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
    fun `closeSession closes the gateway session recorded on the target's endpoint`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val catalog = newCatalog(repo = repo, sessionRepository = sessions, targets = targets)
            sessions.upsertSession("srv", SessionSummary(id = "s1", gatewaySessionId = "gw-dead"))

            val closed = catalog.closeSession("srv", "s1")

            assertTrue(closed)
            assertEquals(listOf("gw-dead"), repo.closed)
            assertNull(sessions.getSession("srv", "s1")?.gatewaySessionId)
        }

    @Test
    fun `closeSession returns false when the target has no gateway leg`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val catalog = newCatalog(repo = repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1", gatewaySessionId = "gw-1"))

            assertFalse(catalog.closeSession("srv", "s1"))
            assertTrue(repo.closed.isEmpty())
            assertEquals("gw-1", sessions.getSession("srv", "s1")?.gatewaySessionId)
        }

    @Test
    fun `deleteSession removes local row and reports known for a cold session`() =
        runTest {
            val sessions = FakeSessionRepo()
            val catalog = newCatalog(sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1"))

            val deleted = catalog.deleteSession("srv", "s1")

            assertTrue(deleted)
            assertNull(sessions.getSession("srv", "s1"))
        }

    @Test
    fun `deleteSession closes the gateway session then drops row and tracking`() =
        runTest {
            val repo = FakeGatewayRepo()
            // The best-effort ACP leg needs no gateway launch here: a
            // mismatched protocol keeps it skipped and the delete still succeeds.
            repo.protocolVersion = "v1"
            val sessions = FakeSessionRepo()
            val hub = newHub(repo = repo, sessionRepository = sessions)
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val catalog = newCatalog(hub = hub, repo = repo, sessionRepository = sessions, targets = targets)
            sessions.upsertSession("srv", SessionSummary(id = "s1"))
            hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")

            val deleted = catalog.deleteSession("srv", "s1")

            assertTrue(deleted)
            assertEquals(listOf("g1"), repo.closed)
            assertNull(sessions.getSession("srv", "s1"))
            assertFalse(hub.isTracked("srv", "s1"))
        }

    @Test
    fun `deleteSession without a gateway leg tears down a tracked chat with no REST call`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val hub = newHub(repo = repo, sessionRepository = sessions)
            val catalog = newCatalog(hub = hub, repo = repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1"))
            hub.registerChat(sessionId = "s1", gatewaySessionId = "g1")

            val deleted = catalog.deleteSession("srv", "s1")

            assertTrue(deleted)
            assertTrue(repo.closed.isEmpty())
            assertNull(sessions.getSession("srv", "s1"))
            assertFalse(hub.isTracked("srv", "s1"))
        }

    @Test
    fun `deleteSession on an unknown session returns false without throwing`() =
        runTest {
            val catalog = newCatalog()

            assertFalse(catalog.deleteSession("srv", "ghost"))
        }

    @Test
    fun `reconcileGatewayLeases clears stale pointers and keeps live ones`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val catalog = newCatalog(repo = repo, sessionRepository = sessions, targets = targets)
            sessions.upsertSession("srv", SessionSummary(id = "alive", gatewaySessionId = "g-live"))
            sessions.upsertSession("srv", SessionSummary(id = "dead", gatewaySessionId = "g-dead"))
            sessions.upsertSession("srv", SessionSummary(id = "plain"))
            repo.sessions =
                listOf(GatewaySessionSummary("g-live", "r-live", "agent-1", "active", "2026-09-22T00:00:00Z"))

            catalog.reconcileGatewayLeases("srv")

            assertEquals("g-live", sessions.getSession("srv", "alive")?.gatewaySessionId)
            assertNull(sessions.getSession("srv", "dead")?.gatewaySessionId)
            assertNull(sessions.getSession("srv", "plain")?.gatewaySessionId)
        }

    @Test
    fun `reconcileGatewayLeases keeps pointers when the gateway list fails`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val catalog = newCatalog(repo = repo, sessionRepository = sessions, targets = targets)
            sessions.upsertSession("srv", SessionSummary(id = "s1", gatewaySessionId = "g-1"))
            repo.listFailure = GatewayRequestException(500, "boom", null)

            catalog.reconcileGatewayLeases("srv")

            assertEquals("g-1", sessions.getSession("srv", "s1")?.gatewaySessionId)
        }

    @Test
    fun `reconcileGatewayLeases leaves pointers alone without a gateway target`() =
        runTest {
            val repo = FakeGatewayRepo()
            val sessions = FakeSessionRepo()
            val catalog = newCatalog(repo = repo, sessionRepository = sessions)
            sessions.upsertSession("srv", SessionSummary(id = "s1", gatewaySessionId = "g-1"))
            // An empty live list would clear the pointer if the reconcile ran at all.
            repo.sessions = emptyList()

            catalog.reconcileGatewayLeases("srv")

            assertEquals("g-1", sessions.getSession("srv", "s1")?.gatewaySessionId)
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
