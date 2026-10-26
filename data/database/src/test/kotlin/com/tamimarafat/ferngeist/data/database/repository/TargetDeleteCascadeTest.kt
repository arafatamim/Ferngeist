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
 * Guards registry deletion and the gateway's binding cascade. Chat history now belongs to
 * listing snapshots, whose target-removal behavior is exercised at the catalog boundary.
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
        servers = ServerRepositoryImpl(database.serverDao(), encryptor)
        gateways =
            GatewaySourceRepositoryImpl(
                database.gatewaySourceDao(),
                encryptor,
            )
        bindings = GatewayAgentBindingRepositoryImpl(database.gatewayAgentBindingDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deleteServer_leavesGatewayBindingsAlone() =
        runTest {
            seedTargets()

            servers.deleteServer(SERVER_ID)

            assertNull(database.serverDao().getServerById(SERVER_ID))
            assertEquals(AGENT_A, database.gatewayAgentBindingDao().getBindingById(AGENT_A)?.id)
        }

    @Test
    fun deleteGateway_cascadesItsBindingsAndLeavesManualServer() =
        runTest {
            seedTargets()

            gateways.deleteGateway(GATEWAY_ID)

            assertNull(database.gatewaySourceDao().getGatewayById(GATEWAY_ID))
            assertNull(database.gatewayAgentBindingDao().getBindingById(AGENT_A))
            assertNull(database.gatewayAgentBindingDao().getBindingById(AGENT_B))
            assertEquals(SERVER_ID, database.serverDao().getServerById(SERVER_ID)?.id)
        }

    @Test
    fun deleteBinding_leavesSiblingAndGateway() =
        runTest {
            seedTargets()

            bindings.deleteBinding(AGENT_A)

            assertNull(database.gatewayAgentBindingDao().getBindingById(AGENT_A))
            assertEquals(AGENT_B, database.gatewayAgentBindingDao().getBindingById(AGENT_B)?.id)
            assertEquals(GATEWAY_ID, database.gatewaySourceDao().getGatewayById(GATEWAY_ID)?.id)
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

    private companion object {
        const val SERVER_ID = "srv-1"
        const val GATEWAY_ID = "gw-1"
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
    }
}
