package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayPairingResult
import com.tamimarafat.ferngeist.gateway.GatewayPushConfig
import com.tamimarafat.ferngeist.gateway.GatewayPushSubscription
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import io.mockk.Runs
import io.mockk.andThenJust
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Behaviour tests for [PushRegistrar]. Runs on an [UnconfinedTestDispatcher] so the
 * internal flow collectors execute eagerly; [backgroundScope] hosts the never-completing
 * collectors so [runTest] doesn't wait on them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrarTest {
    private val gatewayRepository = mockk<GatewayRepository>(relaxed = true)
    private val gatewaySourceRepository = mockk<GatewaySourceRepository>()
    private val unifiedPush = mockk<UnifiedPushClient>(relaxed = true)

    private fun gateway(id: String) =
        GatewaySource(
            id = id,
            name = "gw-$id",
            scheme = "https",
            host = "host-$id",
            gatewayCredential = "cred-$id",
        )

    private fun subscription(endpoint: String) =
        GatewayPushSubscription(endpoint, GatewayPushSubscription.Keys(p256dh = "pk", auth = "au"))

    private fun givenGateways(vararg ids: String): MutableStateFlow<List<GatewaySource>> {
        val gateways = MutableStateFlow(ids.map(::gateway))
        every { gatewaySourceRepository.getGateways() } returns gateways
        return gateways
    }

    init {
        every { unifiedPush.hasDistributor() } returns true
        coEvery { gatewayRepository.getPushConfig(any(), any(), any()) } answers {
            GatewayPushConfig(vapidPublicKey = "vapid-${secondArg<String>()}")
        }
    }

    @Test
    fun `registers every paired gateway with its own VAPID key`() =
        runTest(UnconfinedTestDispatcher()) {
            givenGateways("a", "b")

            registrar().start()

            verify(exactly = 1) { unifiedPush.register("a", "vapid-host-a") }
            verify(exactly = 1) { unifiedPush.register("b", "vapid-host-b") }
        }

    @Test
    fun `waits for a distributor before registering`() =
        runTest(UnconfinedTestDispatcher()) {
            every { unifiedPush.hasDistributor() } returns false
            givenGateways("a")
            val registrar = registrar()

            registrar.start()
            verify(exactly = 0) { unifiedPush.register(any(), any()) }

            registrar.onDistributorReady()
            verify(exactly = 1) { unifiedPush.register("a", "vapid-host-a") }
        }

    @Test
    fun `uploads each endpoint to its own gateway once`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateways = givenGateways("a", "b")
            val registrar = registrar()
            registrar.start()

            registrar.onNewEndpoint("a", subscription("https://push/a"))
            gateways.value = listOf(gateway("a"), gateway("b")) // unrelated emission
            registrar.onNewEndpoint("a", subscription("https://push/a")) // same endpoint again

            coVerify(exactly = 1) {
                gatewayRepository.registerPushSubscription("https", "host-a", "cred-a", subscription("https://push/a"))
            }
            coVerify(exactly = 0) { gatewayRepository.registerPushSubscription(any(), "host-b", any(), any()) }

            // A rotated endpoint is uploaded again.
            registrar.onNewEndpoint("a", subscription("https://push/a2"))
            coVerify(exactly = 1) {
                gatewayRepository.registerPushSubscription("https", "host-a", "cred-a", subscription("https://push/a2"))
            }
        }

    @Test
    fun `retries an upload that failed on the previous emission`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateways = givenGateways("a")
            coEvery {
                gatewayRepository.registerPushSubscription("https", "host-a", "cred-a", any())
            } throws IOException("offline") andThenJust Runs
            val registrar = registrar()
            registrar.start()

            registrar.onNewEndpoint("a", subscription("https://push/a"))
            gateways.value = listOf(gateway("a"), gateway("b"))

            coVerify(exactly = 2) {
                gatewayRepository.registerPushSubscription("https", "host-a", "cred-a", any())
            }
        }

    @Test
    fun `retries a gateway whose push config could not be fetched`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateways = givenGateways("a")
            coEvery {
                gatewayRepository.getPushConfig("https", "host-a", "cred-a")
            } throws IOException("offline") andThen GatewayPushConfig("vapid-a")

            registrar().start()
            verify(exactly = 0) { unifiedPush.register(any(), any()) }

            gateways.value = listOf(gateway("a"), gateway("b"))
            verify(exactly = 1) { unifiedPush.register("a", "vapid-a") }
        }

    @Test
    fun `unregisters a gateway that is removed`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateways = givenGateways("a", "b")
            registrar().start()

            gateways.value = listOf(gateway("a"))

            verify(exactly = 1) { unifiedPush.unregister("b") }
            verify(exactly = 0) { unifiedPush.unregister("a") }
        }

    @Test
    fun `drops and unregisters messages for a gateway no longer paired`() =
        runTest(UnconfinedTestDispatcher()) {
            givenGateways("a")
            val registrar = registrar()

            assertTrue(registrar.acceptsMessageFor("a"))
            assertFalse(registrar.acceptsMessageFor("gone"))
            verify(exactly = 1) { unifiedPush.unregister("gone") }
        }

    @Test
    fun `re-registers after the distributor drops an instance`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateways = givenGateways("a")
            val registrar = registrar()
            registrar.start()

            registrar.onUnregistered("a")
            gateways.value = listOf(gateway("a"), gateway("b"))

            verify(exactly = 2) { unifiedPush.register("a", "vapid-host-a") }
        }

    @Test
    fun `refreshes an expired credential before fetching the push config`() =
        runTest(UnconfinedTestDispatcher()) {
            every { gatewaySourceRepository.getGateways() } returns
                MutableStateFlow(listOf(gateway("a").copy(gatewayCredentialExpiresAt = System.currentTimeMillis())))
            coEvery { gatewayRepository.refreshCredential("https", "host-a", "cred-a") } returns
                GatewayPairingResult(
                    deviceId = "d",
                    deviceName = "n",
                    gatewayCredential = "cred-refreshed",
                    expiresAt = "2099-01-01T00:00:00Z",
                    gatewayId = "gwid-a",
                )
            coEvery { gatewaySourceRepository.updateGateway(any()) } just Runs

            registrar().start()

            coVerify(exactly = 1) { gatewayRepository.getPushConfig("https", "host-a", "cred-refreshed") }
            coVerify(exactly = 1) { gatewaySourceRepository.updateGateway(any()) }
        }

    private fun TestScope.registrar() =
        PushRegistrar(
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            unifiedPush = unifiedPush,
            scope = backgroundScope,
        )
}
