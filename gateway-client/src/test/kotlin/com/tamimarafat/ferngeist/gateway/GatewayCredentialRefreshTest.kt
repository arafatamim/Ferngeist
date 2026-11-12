package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the credential-refresh path in [GatewayRepositoryImpl.refreshCredential],
 * including translation of a gateway 401 into a typed [GatewayCredentialExpiredException]
 * and preservation of the stored credential when refresh cannot produce a valid replacement.
 */
class GatewayCredentialRefreshTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun repoResponding(
        status: HttpStatusCode,
        body: String = "",
    ): GatewayRepositoryImpl {
        val engine =
            MockEngine { request ->
                respond(content = body, status = status)
            }
        return GatewayRepositoryImpl(HttpClient(engine), json)
    }

    @Test
    fun `refreshCredential maps a 401 to GatewayCredentialExpiredException`() =
        runTest {
            val repo = repoResponding(HttpStatusCode.Unauthorized)
            try {
                repo.refreshCredential(
                    scheme = "https",
                    host = "host-a",
                    gatewayCredential = "cred",
                )
                fail("expected GatewayCredentialExpiredException")
            } catch (error: GatewayCredentialExpiredException) {
                assertTrue(error.message.orEmpty().contains("expired", ignoreCase = true))
                assertTrue(error.message.orEmpty().contains("/v1/auth/refresh"))
            }
        }

    @Test
    fun `refreshCredential keeps the credential on a stale-proof 401`() =
        runTest {
            val repo =
                repoResponding(HttpStatusCode.Unauthorized, """{"error":"gateway credential proof expired"}""")
            try {
                repo.refreshCredential(scheme = "https", host = "host-a", gatewayCredential = "cred")
                fail("expected GatewayRequestException")
                // A GatewayCredentialExpiredException escapes this catch and fails the test.
            } catch (error: GatewayRequestException) {
                assertEquals(401, error.statusCode)
            }
        }

    @Test
    fun `refreshCredential passes through non-401 errors`() =
        runTest {
            val repo = repoResponding(HttpStatusCode.InternalServerError, """{"error":"boom"}""")
            try {
                repo.refreshCredential(
                    scheme = "https",
                    host = "host-a",
                    gatewayCredential = "cred",
                )
                fail("expected IllegalStateException")
            } catch (error: GatewayRequestException) {
                assertEquals(500, error.statusCode)
            }
        }

    @Test
    fun `a malformed refresh response preserves the stored gateway credential`() =
        runTest {
            val gateway =
                GatewaySource(
                    id = "gw",
                    name = "PC",
                    host = "host-a",
                    gatewayCredential = "existing-credential",
                    gatewayCredentialExpiresAt = 1_000L,
                )
            val repo = repoResponding(HttpStatusCode.OK, """{"token":"incomplete-replacement"}""")
            val sources = GatewaySources(gateway)

            val result = refreshGatewaySourceIfNeeded(gateway, repo, sources, nowMillis = 1_000L)

            assertEquals(gateway, result)
            assertEquals(gateway, sources.stored)
        }

    @Test
    fun `refreshCredential succeeds and rotates the credential on 200`() =
        runTest {
            val repo =
                repoResponding(
                    HttpStatusCode.OK,
                    """{"deviceId":"d","deviceName":"n","token":"new-token","expiresAt":"2099-01-01T00:00:00Z","scopes":["read"]}""",
                )
            val result =
                repo.refreshCredential(
                    scheme = "https",
                    host = "host-a",
                    gatewayCredential = "old-token",
                )
            assertEquals("d", result.deviceId)
            assertEquals("new-token", result.gatewayCredential)
            assertEquals("2099-01-01T00:00:00Z", result.expiresAt)
        }

    private class GatewaySources(
        var stored: GatewaySource?,
    ) : GatewaySourceRepository {
        override fun getGateways() = flowOf(listOfNotNull(stored))

        override suspend fun addGateway(gateway: GatewaySource) {
            stored = gateway
        }

        override suspend fun updateGateway(gateway: GatewaySource) {
            stored = gateway
        }

        override suspend fun deleteGateway(id: String) {
            stored = stored?.takeUnless { it.id == id }
        }

        override suspend fun getGateway(id: String) = stored?.takeIf { it.id == id }

        override suspend fun getGatewayByGatewayId(gatewayId: String) = stored?.takeIf { it.gatewayId == gatewayId }
    }
}
