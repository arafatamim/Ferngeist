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
 * Integration tests for the Web Push calls of [GatewayRepositoryImpl] exercising the real
 * HTTP path: request building, JSON serialization of the body, and gateway proof-auth.
 * The transport is swapped for Ktor's [MockEngine] so the assertions run against the
 * exact bytes that would go on the wire.
 */
class GatewayPushRegistrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val subscription =
        GatewayPushSubscription(
            endpoint = "https://fcm.googleapis.com/fcm/send/t",
            keys = GatewayPushSubscription.Keys(p256dh = "pk", auth = "au"),
        )

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
    fun `registerPushSubscription posts the subscription to the devices push-token endpoint`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo = repoCapturing { captured = it }

            repo.registerPushSubscription("https", "gw.example.com", "plain-token", subscription)

            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("gw.example.com", request.url.host)
            assertEquals("/v1/devices/push-token", request.url.encodedPath)
            assertEquals(
                """{"subscription":{"endpoint":"https://fcm.googleapis.com/fcm/send/t","keys":{"p256dh":"pk","auth":"au"}},"platform":"webpush"}""",
                (request.body as TextContent).text,
            )
            assertEquals("Bearer plain-token", request.headers["Authorization"])
        }

    @Test
    fun `getPushConfig reads the VAPID public key`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo = repoCapturing(responseBody = """{"vapidPublicKey":"BPUB"}""") { captured = it }

            val config = repo.getPushConfig("https", "gw.example.com", "plain-token")

            assertEquals("BPUB", config.vapidPublicKey)
            val request = requireNotNull(captured)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/devices/push-config", request.url.encodedPath)
            assertEquals("Bearer plain-token", request.headers["Authorization"])
        }

    @Test
    fun `push calls sign the request when the credential carries a proof key`() =
        runTest {
            val proof = GatewayProofAuth.generateProofKey()
            val credential = GatewayProofAuth.encodeStoredCredential("tok-1", proof.privateKey)
            val captured = mutableListOf<HttpRequestData>()
            val repo = repoCapturing(responseBody = """{"vapidPublicKey":"BPUB"}""") { captured += it }

            repo.getPushConfig("http", "10.0.0.2:8080", credential)
            repo.registerPushSubscription("http", "10.0.0.2:8080", credential, subscription)

            captured.forEach { request ->
                assertEquals("Bearer tok-1", request.headers["Authorization"])
                assertTrue(request.headers["X-Ferngeist-Proof-Signature"].orEmpty().isNotBlank())
                assertTrue(request.headers["X-Ferngeist-Proof-Timestamp"].orEmpty().isNotBlank())
                assertTrue(request.headers["X-Ferngeist-Proof-Nonce"].orEmpty().isNotBlank())
            }
            assertEquals(2, captured.size)
        }

    @Test
    fun `registerPushSubscription omits proof headers for a bare bearer credential`() =
        runTest {
            var captured: HttpRequestData? = null
            val repo = repoCapturing { captured = it }

            repo.registerPushSubscription("https", "gw.example.com", "plain-token", subscription)

            assertNull(requireNotNull(captured).headers["X-Ferngeist-Proof-Signature"])
        }

    @Test
    fun `registerPushSubscription throws on a non-success response`() =
        runTest {
            val repo = repoCapturing(status = HttpStatusCode.InternalServerError, responseBody = "boom")

            try {
                repo.registerPushSubscription("https", "gw.example.com", "plain-token", subscription)
                fail("expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("push-token"))
                assertTrue(e.message.orEmpty().contains("boom"))
            }
        }
}
