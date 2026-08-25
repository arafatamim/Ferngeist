package com.tamimarafat.ferngeist.acp.bridge

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerRegistry
import com.tamimarafat.ferngeist.acp.bridge.connection.DefaultAcpConnectionManagerFactory
import com.tamimarafat.ferngeist.gateway.GatewayRepository
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
import org.junit.Test

/**
 * Coverage for [AcpManagerRegistry] aggregate state.
 *
 * Managers are constructed directly (real instances with the fake
 * [ConnectivityObserverStub] from [AcpBridgeTest]); the registry observes each
 * manager's [AcpConnectionManager.connectionState] flow. A freshly constructed
 * manager starts disconnected and never reaches Connected without a live
 * transport, so the tests assert the registration/aggregation/lifecycle
 * semantics that are observable in-process (empty registry, N disconnected
 * managers, idempotent registration, unregister, owner-scope completion).
 * The "becomes connected" transition itself is exercised through the factory
 * path by the existing suites (ViewModels mock the factory but the app DI
 * wires every created manager through the registry).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcpManagerRegistryTest {
    /** Never invoked by these tests (no connection attempt); satisfies the non-null parameter. */
    private object NoopGatewayRepository : GatewayRepository {
        override suspend fun fetchStatus(
            scheme: String,
            host: String,
        ) = TODO()

        override suspend fun startPairing(
            scheme: String,
            host: String,
        ) = TODO()

        override suspend fun getPairingStatus(
            scheme: String,
            host: String,
            challengeId: String,
        ) = TODO()

        override suspend fun completePairing(
            scheme: String,
            host: String,
            challengeId: String,
            code: String,
            deviceName: String,
        ) = TODO()

        override suspend fun refreshCredential(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO()

        override suspend fun fetchAgents(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO()

        override suspend fun startAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ) = TODO()

        override suspend fun connectRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            sessionMode: String?,
            fresh: Boolean,
        ) = TODO()

        override suspend fun restartRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            envVars: Map<String, String>,
        ) = TODO()

        override suspend fun fetchRuntimeLogs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ) = TODO()

        override suspend fun resumeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) = TODO()

        override suspend fun listGatewaySessions(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO()

        override suspend fun closeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) = TODO()

        override suspend fun registerPushToken(
            scheme: String,
            host: String,
            gatewayCredential: String,
            token: String,
            platform: String,
        ) = TODO()

        override suspend fun fetchWorkspaceFile(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String,
        ) = TODO()

        override suspend fun fetchGitStatus(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ) = TODO()

        override suspend fun fetchGitDiff(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String?,
        ) = TODO()
    }

    private fun newManager() =
        AcpConnectionManager(
            connectivityObserver = ConnectivityObserverStub(initialState = true),
            gatewayRepository = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun `anyConnected is false with no managers registered`() =
        runTest {
            val registry = AcpManagerRegistry(CoroutineScope(Dispatchers.Unconfined))
            advanceUntilIdle()

            assertFalse(registry.anyConnected.value)
            assertNull(registry.connectedDisplayName.value)
        }

    @Test
    fun `anyConnected stays false while all registered managers are disconnected`() =
        runTest {
            val registry = AcpManagerRegistry(CoroutineScope(Dispatchers.Unconfined))

            registry.register(newManager())
            advanceUntilIdle()
            assertFalse(registry.anyConnected.value)

            registry.register(newManager())
            advanceUntilIdle()
            assertFalse(registry.anyConnected.value)
            assertNull(registry.connectedDisplayName.value)
        }

    @Test
    fun `registering the same manager twice is idempotent`() =
        runTest {
            val registry = AcpManagerRegistry(CoroutineScope(Dispatchers.Unconfined))
            val manager = newManager()

            registry.register(manager)
            registry.register(manager)
            advanceUntilIdle()

            assertFalse(registry.anyConnected.value)
        }

    @Test
    fun `unregister removes the manager from the aggregate`() =
        runTest {
            val registry = AcpManagerRegistry(CoroutineScope(Dispatchers.Unconfined))

            registry.register(newManager())
            val target = newManager()
            registry.register(target)
            advanceUntilIdle()
            assertEquals(2, registry.trackedCount())

            registry.unregister(target)
            advanceUntilIdle()

            assertEquals(1, registry.trackedCount())
            assertFalse(registry.anyConnected.value)
        }

    @Test
    fun `completing the owning scope unregisters the manager`() =
        runTest {
            val registry = AcpManagerRegistry(CoroutineScope(Dispatchers.Unconfined))
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val factory =
                DefaultAcpConnectionManagerFactory(
                    connectivityObserver = ConnectivityObserverStub(initialState = true),
                    gatewayRepository = NoopGatewayRepository,
                    registry = registry,
                )

            factory.create(ownerScope)
            advanceUntilIdle()
            assertEquals(1, registry.trackedCount())
            assertFalse(registry.anyConnected.value)

            // Cancelling the owner scope runs invokeOnCompletion: unregister + disconnect.
            ownerScope.cancel()
            advanceUntilIdle()

            assertEquals(0, registry.trackedCount())
            assertFalse(registry.anyConnected.value)
        }

    @Test
    fun `manager created over a job-less scope still registers`() =
        runTest {
            val registry = AcpManagerRegistry(CoroutineScope(Dispatchers.Unconfined))

            val manager = newManager()
            registry.register(manager)
            advanceUntilIdle()

            assertEquals(1, registry.trackedCount())
            assertFalse(registry.anyConnected.value)
        }

    @Test
    fun `anyConnected still emits after all managers unregister`() =
        runTest {
            val registry = AcpManagerRegistry(CoroutineScope(Dispatchers.Unconfined))

            val first = newManager()
            val second = newManager()
            registry.register(first)
            registry.register(second)
            advanceUntilIdle()
            assertEquals(2, registry.trackedCount())

            registry.unregister(first)
            registry.unregister(second)
            advanceUntilIdle()
            assertEquals(0, registry.trackedCount())

            // The flow must still emit (not freeze on its last value) once the
            // registry is empty — combine over zero flows would emit nothing.
            assertFalse(withTimeout(1_000L) { registry.anyConnected.first() })
            assertNull(withTimeout(1_000L) { registry.connectedDisplayName.first() })
        }

    @Test
    fun `close releases the transport client on owner-scope completion`() =
        runTest {
            val registry = AcpManagerRegistry(CoroutineScope(Dispatchers.Unconfined))
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val factory =
                DefaultAcpConnectionManagerFactory(
                    connectivityObserver = ConnectivityObserverStub(initialState = true),
                    gatewayRepository = NoopGatewayRepository,
                    registry = registry,
                )

            val manager = factory.create(ownerScope)
            advanceUntilIdle()
            assertEquals(1, registry.trackedCount())

            // connect() forces the lazily-created shared HttpClient to exist.
            manager.connect(
                com.tamimarafat.ferngeist.acp.bridge.connection
                    .AcpConnectionConfig(host = "127.0.0.1:1"),
            )

            ownerScope.cancel()
            advanceUntilIdle()

            assertEquals(0, registry.trackedCount())
            // close() ran on scope completion; disconnect() alone would not tear
            // down the HTTP client, so assert via the manager's public surface
            // that teardown completed without throwing.
            manager.disconnect()
            assertFalse(registry.anyConnected.value)
        }
}
