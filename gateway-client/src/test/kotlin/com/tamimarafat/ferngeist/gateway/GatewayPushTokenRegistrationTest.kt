package com.tamimarafat.ferngeist.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Integration tests for [GatewayRepositoryImpl.registerPushToken] exercising the real
 * HTTP path: request building, JSON serialization of the body, and gateway proof-auth.
 * The transport is swapped for Ktor's [MockEngine] so the assertions run against the
 * exact bytes that would go on the wire.
 */
class GatewayPushTokenRegistrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun repoCapturing(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "",
        onRequest: (HttpRequestData) -> Unit = {},
    ): GatewayRepositoryImpl {
        val engine =
            MockEngine { request ->
                onRequest(request)
                respond(content = responseBody, status = status)
            }
        return GatewayRepositoryImpl(HttpClient(engine), json)
    }

    @Test
    fun `registerPushToken posts token to the devices push-token endpoint`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo = repoCapturing { captured = it }

            repo.registerPushToken(
                scheme = "https",
                host = "gw.example.com",
                gatewayCredential = "plain-token",
                token = "fcm-abc",
            )

            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https", request.url.protocol.name)
            assertEquals("gw.example.com", request.url.host)
            assertEquals("/v1/devices/push-token", request.url.encodedPath)

            val body = (request.body as TextContent).text
            assertEquals(
                json.encodeToString(GatewayPushTokenRequest.serializer(), GatewayPushTokenRequest("fcm-abc", "android")),
                body,
            )
            assertEquals("""{"token":"fcm-abc","platform":"android"}""", body)
            assertEquals("Bearer plain-token", request.headers["Authorization"])
        }

    @Test
    fun `registerPushToken forwards an explicit platform`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo = repoCapturing { captured = it }

            repo.registerPushToken(
                scheme = "http",
                host = "10.0.0.2:8080",
                gatewayCredential = "plain-token",
                token = "fcm-abc",
                platform = "android-tv",
            )

            val body = (requireNotNull(captured).body as TextContent).text
            assertEquals("""{"token":"fcm-abc","platform":"android-tv"}""", body)
        }

    @Test
    fun `registerPushToken signs the request when the credential carries a proof key`() =
        runTest {
            val proof = GatewayProofAuth.generateProofKey()
            val credential = GatewayProofAuth.encodeStoredCredential("tok-1", proof.privateKey)
            var captured: HttpRequestData? = null
            val repo = repoCapturing { captured = it }

            repo.registerPushToken("http", "10.0.0.2:8080", credential, "fcm-xyz")

            val request = requireNotNull(captured)
            assertEquals("Bearer tok-1", request.headers["Authorization"])
            assertTrue(request.headers["X-Ferngeist-Proof-Signature"].orEmpty().isNotBlank())
            assertTrue(request.headers["X-Ferngeist-Proof-Timestamp"].orEmpty().isNotBlank())
            assertTrue(request.headers["X-Ferngeist-Proof-Nonce"].orEmpty().isNotBlank())
        }

    @Test
    fun `registerPushToken omits proof headers for a bare bearer credential`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo = repoCapturing { captured = it }

            repo.registerPushToken("https", "gw.example.com", "plain-token", "fcm-abc")

            val request = requireNotNull(captured)
            assertNull(request.headers["X-Ferngeist-Proof-Signature"])
        }

    @Test
    fun `registerPushToken throws on a non-success response`() =
        runTest {
            val repo = repoCapturing(status = HttpStatusCode.InternalServerError, responseBody = "boom")

            try {
                repo.registerPushToken("https", "gw.example.com", "plain-token", "fcm-abc")
                fail("expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("push-token"))
                assertTrue(e.message.orEmpty().contains("boom"))
            }
        }
}
