package com.tamimarafat.ferngeist.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the credential-refresh path in [GatewayRepositoryImpl.refreshCredential],
 * specifically the translation of a gateway 401 into a typed
 * [GatewayCredentialExpiredException] so callers can clear the dead credential.
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
}
