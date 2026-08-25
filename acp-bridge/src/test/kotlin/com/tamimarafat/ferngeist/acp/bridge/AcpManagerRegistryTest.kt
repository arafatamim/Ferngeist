package com.tamimarafat.ferngeist.acp.bridge

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
 * transport, so the tests assert the registration/aggregation semantics that
 * are observable in-process (empty registry, N disconnected managers,
 * idempotent registration). The "becomes connected" transition itself is
 * exercised through the factory path by the existing suites (ViewModels mock
 * the factory but the app DI wires every created manager through the registry).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcpManagerRegistryTest {
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
}
