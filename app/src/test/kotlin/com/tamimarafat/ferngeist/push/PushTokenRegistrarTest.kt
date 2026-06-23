package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayPairingResult
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import io.mockk.Runs
import io.mockk.andThenJust
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behaviour tests for [PushTokenRegistrar]. Runs on an [UnconfinedTestDispatcher] so the
 * internal flow collector and one-shot launches execute eagerly; [backgroundScope] hosts
 * the never-completing collector so [runTest] doesn't wait on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushTokenRegistrarTest {
    private val gatewayRepository = mockk<GatewayRepository>(relaxed = true)
    private val gatewaySourceRepository = mockk<GatewaySourceRepository>()
    private val pushTokenStore = mockk<PushTokenStore>()

    private fun gateway(id: String) =
        GatewaySource(
            id = id,
            name = "gw-$id",
            scheme = "https",
            host = "host-$id",
            gatewayCredential = "cred-$id",
        )

    @Test
    fun `registers the token with every paired gateway`() =
        runTest(UnconfinedTestDispatcher()) {
            every { pushTokenStore.token } returns MutableStateFlow("fcm-1")
            every { gatewaySourceRepository.getGateways() } returns
                MutableStateFlow(listOf(gateway("a"), gateway("b")))

            registrar().start()

            coVerify(exactly = 1) {
                gatewayRepository.registerPushToken("https", "host-a", "cred-a", "fcm-1", any())
            }
            coVerify(exactly = 1) {
                gatewayRepository.registerPushToken("https", "host-b", "cred-b", "fcm-1", any())
            }
        }

    @Test
    fun `does not re-register gateways already holding the token`() =
        runTest(UnconfinedTestDispatcher()) {
            every { pushTokenStore.token } returns MutableStateFlow("fcm-1")
            val gateways = MutableStateFlow(listOf(gateway("a")))
            every { gatewaySourceRepository.getGateways() } returns gateways

            registrar().start()

            // A newly paired gateway appears; the existing one must not be re-POSTed.
            gateways.value = listOf(gateway("a"), gateway("b"))

            coVerify(exactly = 1) {
                gatewayRepository.registerPushToken("https", "host-a", "cred-a", "fcm-1", any())
            }
            coVerify(exactly = 1) {
                gatewayRepository.registerPushToken("https", "host-b", "cred-b", "fcm-1", any())
            }
        }

    @Test
    fun `retries a gateway that failed on the previous emission`() =
        runTest(UnconfinedTestDispatcher()) {
            every { pushTokenStore.token } returns MutableStateFlow("fcm-1")
            val gateways = MutableStateFlow(listOf(gateway("a")))
            every { gatewaySourceRepository.getGateways() } returns gateways
            coEvery {
                gatewayRepository.registerPushToken("https", "host-a", "cred-a", "fcm-1", any())
            } throws RuntimeException("offline") andThenJust Runs

            registrar().start()

            // Force a new emission so the failed gateway is retried.
            gateways.value = listOf(gateway("a"), gateway("b"))

            coVerify(exactly = 2) {
                gatewayRepository.registerPushToken("https", "host-a", "cred-a", "fcm-1", any())
            }
        }

    @Test
    fun `does nothing while the token is blank`() =
        runTest(UnconfinedTestDispatcher()) {
            every { pushTokenStore.token } returns MutableStateFlow("")
            every { gatewaySourceRepository.getGateways() } returns MutableStateFlow(listOf(gateway("a")))

            registrar().start()

            coVerify(exactly = 0) {
                gatewayRepository.registerPushToken(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `registers once a token becomes available`() =
        runTest(UnconfinedTestDispatcher()) {
            val token = MutableStateFlow<String?>(null)
            every { pushTokenStore.token } returns token
            every { gatewaySourceRepository.getGateways() } returns MutableStateFlow(listOf(gateway("a")))

            registrar().start()
            coVerify(exactly = 0) { gatewayRepository.registerPushToken(any(), any(), any(), any(), any()) }

            token.value = "fcm-late"

            coVerify(exactly = 1) {
                gatewayRepository.registerPushToken("https", "host-a", "cred-a", "fcm-late", any())
            }
        }

    @Test
    fun `onTokenRefreshed persists the new token`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { pushTokenStore.setToken(any()) } just Runs

            registrar().onTokenRefreshed("fresh-token")

            coVerify(exactly = 1) { pushTokenStore.setToken("fresh-token") }
        }

    @Test
    fun `onTokenRefreshed ignores a blank token`() =
        runTest(UnconfinedTestDispatcher()) {
            registrar().onTokenRefreshed("")

            coVerify(exactly = 0) { pushTokenStore.setToken(any()) }
        }

    @Test
    fun `refreshes an expired credential before registering`() =
        runTest(UnconfinedTestDispatcher()) {
            every { pushTokenStore.token } returns MutableStateFlow("fcm-1")
            val gateway = gateway("a").copy(
                gatewayCredentialExpiresAt = System.currentTimeMillis(),
            )
            every { gatewaySourceRepository.getGateways() } returns
                MutableStateFlow(listOf(gateway))
            coEvery {
                gatewayRepository.refreshCredential("https", "host-a", "cred-a")
            } returns GatewayPairingResult(
                deviceId = "d",
                deviceName = "n",
                gatewayCredential = "cred-refreshed",
                expiresAt = "2099-01-01T00:00:00Z",
                gatewayId = "gwid-a",
            )
            coEvery { gatewaySourceRepository.updateGateway(any()) } just Runs

            registrar().start()

            coVerify(exactly = 1) {
                gatewayRepository.registerPushToken("https", "host-a", "cred-refreshed", "fcm-1", any())
            }
            coVerify(exactly = 1) { gatewaySourceRepository.updateGateway(any()) }
        }

    @Test
    fun `re-registers after a re-pair with the same local id but new credential`() =
        runTest(UnconfinedTestDispatcher()) {
            every { pushTokenStore.token } returns MutableStateFlow("fcm-1")
            val gateways = MutableStateFlow(listOf(gateway("a")))
            every { gatewaySourceRepository.getGateways() } returns gateways

            registrar().start()

            gateways.value = listOf(
                gateway("a").copy(gatewayCredential = "cred-a-new"),
            )

            coVerify(exactly = 1) {
                gatewayRepository.registerPushToken("https", "host-a", "cred-a", "fcm-1", any())
            }
            coVerify(exactly = 1) {
                gatewayRepository.registerPushToken("https", "host-a", "cred-a-new", "fcm-1", any())
            }
        }

    private fun kotlinx.coroutines.test.TestScope.registrar() =
        PushTokenRegistrar(
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            pushTokenStore = pushTokenStore,
            scope = backgroundScope,
        )
}
