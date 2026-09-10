package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests that [launchGatewayRuntime] propagates cancellation instead of reporting
 * it as a launch failure, as its KDoc promises.
 *
 * A launch runs inside the caller's scope (a chat screen or a list refresh), so a
 * cancelled scope must cancel the launch. Folding the [kotlinx.coroutines.CancellationException]
 * into a `Result.failure` would instead hand the caller a failure value to render —
 * a spurious "failed to connect" on a screen the user already left.
 */
class GatewayLaunchRuntimeCancellationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a cancelled launch propagates instead of reporting a failure`() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/start")) {
                        // The caller's scope going away mid-launch. Thrown from the
                        // repository call the launch awaits, so it surfaces exactly
                        // where a real cancellation would.
                        throw CancellationException("scope gone")
                    }
                    respond("{}", HttpStatusCode.OK)
                }
            val repository = GatewayRepositoryImpl(HttpClient(engine), json)

            val thrown =
                try {
                    launchGatewayRuntime(
                        gatewayRepository = repository,
                        gatewaySourceRepository = FakeGatewaySources(),
                        gatewaySource = SOURCE,
                        agentId = "pi-acp",
                    )
                    null
                } catch (error: CancellationException) {
                    error
                }

            assertNotNull(
                "cancellation must propagate out of the launch, not be folded into Result.failure",
                thrown,
            )
        }

    private class FakeGatewaySources : GatewaySourceRepository {
        override fun getGateways(): Flow<List<GatewaySource>> = flowOf(emptyList())

        override suspend fun addGateway(gateway: GatewaySource) = Unit

        override suspend fun updateGateway(gateway: GatewaySource) = Unit

        override suspend fun deleteGateway(id: String) = Unit

        override suspend fun getGateway(id: String): GatewaySource? = null

        override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? = null
    }

    private companion object {
        val SOURCE =
            GatewaySource(
                id = "source-1",
                name = "gw",
                scheme = "http",
                host = "gw.local:5788",
                gatewayCredential = "cred",
            )
    }
}
