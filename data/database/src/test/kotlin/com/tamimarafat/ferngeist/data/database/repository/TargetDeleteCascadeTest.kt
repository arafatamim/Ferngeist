package com.tamimarafat.ferngeist.data.database.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import com.tamimarafat.ferngeist.data.database.crypto.CredentialEncryptor
import com.tamimarafat.ferngeist.data.database.entity.GatewayAgentBindingEntity
import com.tamimarafat.ferngeist.data.database.entity.GatewaySourceEntity
import com.tamimarafat.ferngeist.data.database.entity.ServerEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards that removing a launchable target takes its session history with it.
 *
 * Sessions are keyed by target id: a manual server's row id, or a gateway agent
 * binding's id. Every way in resolves the target first, so a session whose target is
 * gone is unreachable history — it still renders (the recent list reads sessions, and
 * the recents gate then hides it) while no entry point can ever open it.
 *
 * The gateway case is the one that needs care: its bindings disappear with the FK
 * cascade on the gateway row, so their ids have to be read before the delete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TargetDeleteCascadeTest {
    private lateinit var database: FerngeistDatabase
    private lateinit var servers: ServerRepositoryImpl
    private lateinit var gateways: GatewaySourceRepositoryImpl
    private lateinit var bindings: GatewayAgentBindingRepositoryImpl

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    FerngeistDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        // These paths only call delete() on the encryptor, which is a DataStore write;
        // the Keystore-backed encrypt/decrypt is never reached, so the real one is fine.
        val encryptor = CredentialEncryptor(ApplicationProvider.getApplicationContext())
        servers = ServerRepositoryImpl(database.serverDao(), encryptor, database.sessionDao())
        gateways =
            GatewaySourceRepositoryImpl(
                database.gatewaySourceDao(),
                encryptor,
                database.sessionDao(),
                database.gatewayAgentBindingDao(),
            )
        bindings = GatewayAgentBindingRepositoryImpl(database.gatewayAgentBindingDao(), database.sessionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deleteServer_dropsItsSessionsAndLeavesEveryOtherTargetAlone() =
        runTest {
            seedTargets()

            servers.deleteServer(SERVER_ID)

            assertNull(session("chat-server"))
            assertNull("the server row itself is gone", database.serverDao().getServerById(SERVER_ID))
            assertEquals(
                "another agent's history is not in scope",
                "chat-agent-a",
                session("chat-agent-a")?.sessionId,
            )
            assertEquals(
                "an unrelated target's history is not in scope",
                "chat-other",
                session("chat-other")?.sessionId,
            )
        }

    @Test
    fun deleteGateway_dropsItsAgentsSessionsEvenThoughTheBindingsCascade() =
        runTest {
            seedTargets()

            gateways.deleteGateway(GATEWAY_ID)

            assertNull("the first agent's history", session("chat-agent-a"))
            assertNull("the second agent's history", session("chat-agent-b"))
            assertNull("the gateway row", database.gatewaySourceDao().getGatewayById(GATEWAY_ID))
            assertNull("its bindings cascade", database.gatewayAgentBindingDao().getBindingById(AGENT_A))
            assertSiblingsUntouched()
        }

    @Test
    fun deleteBinding_dropsOnlyThatAgentsSessions() =
        runTest {
            seedTargets()

            bindings.deleteBinding(AGENT_A)

            assertNull(session("chat-agent-a"))
            assertEquals("the other agent keeps its history", "chat-agent-b", session("chat-agent-b")?.sessionId)
            assertEquals("chat-server", session("chat-server")?.sessionId)
        }

    private suspend fun assertSiblingsUntouched() {
        assertEquals("the manual server keeps its history", "chat-server", session("chat-server")?.sessionId)
        assertEquals("an unrelated target's history is not in scope", "chat-other", session("chat-other")?.sessionId)
    }

    private suspend fun seedTargets() {
        database.serverDao().insertServer(
            ServerEntity(
                id = SERVER_ID,
                name = "Manual",
                scheme = "ws",
                host = "host:1",
                token = "token",
                preferredAuthMethodId = null,
            ),
        )
        database.gatewaySourceDao().insertGateway(
            GatewaySourceEntity(
                id = GATEWAY_ID,
                name = "Gateway",
                scheme = "http",
                host = "host:2",
                gatewayCredential = "credential",
                gatewayCredentialExpiresAt = null,
                gatewayRemoteMode = null,
            ),
        )
        database.gatewayAgentBindingDao().insertBinding(binding(AGENT_A))
        database.gatewayAgentBindingDao().insertBinding(binding(AGENT_B))
        seedSession("chat-server", SERVER_ID)
        seedSession("chat-agent-a", AGENT_A)
        seedSession("chat-agent-b", AGENT_B)
        seedSession("chat-other", "srv-other")
    }

    private fun binding(id: String) =
        GatewayAgentBindingEntity(
            id = id,
            name = id,
            gatewaySourceId = GATEWAY_ID,
            agentId = "agent-$id",
            preferredAuthMethodId = null,
            icon = null,
        )

    private suspend fun seedSession(
        sessionId: String,
        serverId: String,
    ) {
        database.sessionDao().upsertSession(
            sessionId = sessionId,
            serverId = serverId,
            title = null,
            cwd = null,
            updatedAt = null,
            gatewaySessionId = null,
        )
    }

    private suspend fun session(sessionId: String) = database.sessionDao().getSessionById(sessionId)

    private companion object {
        const val SERVER_ID = "srv-1"
        const val GATEWAY_ID = "gw-1"
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
    }
}
