package com.tamimarafat.ferngeist.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Clock-skew recovery: a device clock off by more than the gateway's ±5-minute proof
 * window makes every signed call return `proof_expired`. The app learns the gateway's
 * clock from a rejection (body `serverTime`, or the HTTP `Date` header on old
 * gateways), re-signs with a fresh nonce, and retries exactly once — reproduced in
 * the field at a 37-minute skew.
 */
class GatewayProofSkewTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** What one wire request looked like from the client's side. */
    private class CapturedRequest(
        val proofTimestamp: String?,
        val proofNonce: String?,
    )

    private class Stub(
        val status: HttpStatusCode,
        val body: String,
        /** An instant to expose in the HTTP `Date` header (old-gateway fallback clock). */
        val dateHeader: Instant? = null,
    )

    private fun engineOf(
        capturedRequests: MutableList<CapturedRequest>,
        stubs: List<Stub>,
    ): MockEngine =
        MockEngine { request ->
            val index = capturedRequests.size
            capturedRequests +=
                CapturedRequest(
                    request.headers["X-Ferngeist-Proof-Timestamp"],
                    request.headers["X-Ferngeist-Proof-Nonce"],
                )
            val stub = stubs[index.coerceAtMost(stubs.size - 1)]
            val headers =
                stub.dateHeader
                    ?.let { headersOf(HttpHeaders.Date to listOf(httpDate(it))) }
                    ?: headersOf()
            respond(content = stub.body, status = stub.status, headers = headers)
        }

    private fun proofCredential(): String {
        val key = GatewayProofAuth.generateProofKey()
        return GatewayProofAuth.encodeStoredCredential("token-1", key.privateKey)
    }

    @Test
    fun `proof_expired 401 teaches the clock and the re-signed retry succeeds`() =
        runTest {
            val serverNow = Instant.now().plusSeconds(SKEW_SECONDS)
            val captured = mutableListOf<CapturedRequest>()
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(
                            captured,
                            listOf(
                                Stub(HttpStatusCode.Unauthorized, proofExpiredBody(serverNow)),
                                Stub(HttpStatusCode.OK, """{"agents":[]}"""),
                            ),
                        ),
                    ),
                    json,
                )

            val agents = repo.fetchAgents("https", HOST, proofCredential())

            assertEquals(emptyList<GatewayAgent>(), agents)
            assertEquals(2, captured.size)
            // The first signature trusted the device's skewed clock, the second was
            // signed against the gateway's.
            assertNear(serverNow.epochSecond - SKEW_SECONDS, captured[0].timestampSeconds(), 2)
            assertNear(serverNow.epochSecond, captured[1].timestampSeconds(), 2)
            assertNotEquals(captured[0].proofNonce, captured[1].proofNonce)
        }

    @Test
    fun `old gateway without code or serverTime re-signs with the Date header`() =
        runTest {
            val serverNow = Instant.now().plusSeconds(SKEW_SECONDS)
            // Neither `code` nor `serverTime`: the pairing falls back to the wording.
            val captured = mutableListOf<CapturedRequest>()
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(
                            captured,
                            listOf(
                                Stub(
                                    HttpStatusCode.Unauthorized,
                                    """{"error":"gateway credential proof expired"}""",
                                    dateHeader = serverNow,
                                ),
                                Stub(HttpStatusCode.OK, """{"agents":[]}"""),
                            ),
                        ),
                    ),
                    json,
                )

            val agents = repo.fetchAgents("https", HOST, proofCredential())

            assertEquals(emptyList<GatewayAgent>(), agents)
            assertEquals(2, captured.size)
            assertNear(serverNow.epochSecond - SKEW_SECONDS, captured[0].timestampSeconds(), 2)
            assertNear(serverNow.epochSecond, captured[1].timestampSeconds(), 5)
        }

    @Test
    fun `learned offset persists per host and a different host starts at zero`() =
        runTest {
            val serverNow = Instant.now().plusSeconds(SKEW_SECONDS)
            val clock = GatewayClock()
            // Recover on host A the usual way: 401 proof_expired, then a success.
            val firstCaptured = mutableListOf<CapturedRequest>()
            val recoveringRepo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(
                            firstCaptured,
                            listOf(
                                Stub(HttpStatusCode.Unauthorized, proofExpiredBody(serverNow)),
                                Stub(HttpStatusCode.OK, """{"agents":[]}"""),
                                Stub(HttpStatusCode.OK, """{"agents":[]}"""),
                            ),
                        ),
                    ),
                    json,
                    clock,
                )
            recoveringRepo.fetchAgents("https", HOST, proofCredential())
            assertEquals(2, firstCaptured.size)
            // The next request to the same host must be inside the window on the
            // very first attempt — no second 401 teaches it.
            recoveringRepo.fetchAgents("https", HOST, proofCredential())
            assertEquals(3, firstCaptured.size)
            assertNotEquals(firstCaptured[1].proofNonce, firstCaptured[2].proofNonce)
            assertNear(serverNow.epochSecond, firstCaptured[2].timestampSeconds(), 2)
            // A different host was never rejected, so it still signs with offset 0.
            val otherCaptured = mutableListOf<CapturedRequest>()
            val otherRepo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(otherCaptured, listOf(Stub(HttpStatusCode.OK, """{"agents":[]}"""))),
                    ),
                    json,
                    clock,
                )
            otherRepo.fetchAgents("https", OTHER_HOST, proofCredential())
            assertEquals(1, otherCaptured.size)
            assertNear(serverNow.epochSecond - SKEW_SECONDS, otherCaptured[0].timestampSeconds(), 2)
        }

    @Test
    fun `a 200 status with serverTime seeds the offset before the first signed call`() =
        runTest {
            val serverNow = Instant.now().plusSeconds(SKEW_SECONDS)
            val captured = mutableListOf<CapturedRequest>()
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(
                            captured,
                            listOf(
                                Stub(HttpStatusCode.OK, statusBody(serverNow)),
                                Stub(HttpStatusCode.OK, """{"agents":[]}"""),
                            ),
                        ),
                    ),
                    json,
                )
            val credential = proofCredential()

            repo.fetchStatus("https", HOST)
            repo.fetchAgents("https", HOST, credential)

            assertEquals(2, captured.size)
            // fetchStatus is unauthenticated: no proof headers on the handshake.
            assertNull(captured[0].proofTimestamp)
            assertNear(serverNow.epochSecond, captured[1].timestampSeconds(), 2)
        }

    @Test
    fun `two consecutive proof_expired rejections throw with the code and never retry twice`() =
        runTest {
            val serverNow = Instant.now().plusSeconds(SKEW_SECONDS)
            val captured = mutableListOf<CapturedRequest>()
            val rejection = proofExpiredBody(serverNow)
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(
                            captured,
                            listOf(
                                Stub(HttpStatusCode.Unauthorized, rejection),
                                Stub(HttpStatusCode.Unauthorized, rejection),
                            ),
                        ),
                    ),
                    json,
                )

            try {
                repo.fetchAgents("https", HOST, proofCredential())
                fail("expected GatewayRequestException")
            } catch (error: GatewayRequestException) {
                assertEquals(401, error.statusCode)
                assertEquals("proof_expired", error.code)
            }
            assertEquals(2, captured.size)
        }

    @Test
    fun `credential_invalid 401 throws without retrying`() =
        runTest {
            val captured = mutableListOf<CapturedRequest>()
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(
                            captured,
                            listOf(
                                Stub(
                                    HttpStatusCode.Unauthorized,
                                    """{"error":"gateway credential invalid","code":"credential_invalid"}""",
                                ),
                                Stub(
                                    HttpStatusCode.Unauthorized,
                                    """{"error":"gateway credential invalid","code":"credential_invalid"}""",
                                ),
                            ),
                        ),
                    ),
                    json,
                )

            try {
                repo.fetchAgents("https", HOST, proofCredential())
                fail("expected GatewayRequestException")
            } catch (error: GatewayRequestException) {
                assertEquals(401, error.statusCode)
                assertEquals("credential_invalid", error.code)
            }
            assertEquals(1, captured.size)
        }

    @Test
    fun `refreshCredential on proof_expired keeps the credential`() =
        runTest {
            val serverNow = Instant.now().plusSeconds(SKEW_SECONDS)
            val captured = mutableListOf<CapturedRequest>()
            val body = proofExpiredBody(serverNow)
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(
                            captured,
                            listOf(Stub(HttpStatusCode.Unauthorized, body), Stub(HttpStatusCode.Unauthorized, body)),
                        ),
                    ),
                    json,
                    GatewayClock(),
                )

            try {
                repo.refreshCredential("https", HOST, proofCredential())
                fail("expected GatewayRequestException")
            } catch (error: GatewayRequestException) {
                // A GatewayCredentialExpiredException would have escaped and failed the test.
                assertEquals("proof_expired", error.code)
            }
        }

    @Test
    fun `refreshCredential throws GatewayCredentialExpiredException on credential_grace_expired`() =
        runTest {
            val captured = mutableListOf<CapturedRequest>()
            val body = """{"error":"gateway credential expired","code":"credential_grace_expired","serverTime":"${rfc3339(
                Instant.now(),
            )}"}"""
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(engineOf(captured, listOf(Stub(HttpStatusCode.Unauthorized, body)))),
                    json,
                )

            try {
                repo.refreshCredential("https", HOST, proofCredential())
                fail("expected GatewayCredentialExpiredException")
            } catch (error: GatewayCredentialExpiredException) {
                assertTrue(error.message.orEmpty().contains("/v1/auth/refresh"))
            }
            // A dead credential is not recoverable by re-signing: no retry.
            assertEquals(1, captured.size)
        }

    @Test
    fun `the machine-readable code wins over the error text`() =
        runTest {
            val captured = mutableListOf<CapturedRequest>()
            // The wording says nothing about proofs, the code says proof_expired.
            val body =
                """{"error":"credential completely rejected","code":"proof_expired",""" +
                    """"serverTime":"${rfc3339(Instant.now().plusSeconds(SKEW_SECONDS))}"}"""
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(
                            captured,
                            listOf(Stub(HttpStatusCode.Unauthorized, body), Stub(HttpStatusCode.Unauthorized, body)),
                        ),
                    ),
                    json,
                )

            try {
                repo.refreshCredential("https", HOST, proofCredential())
                fail("expected GatewayRequestException")
            } catch (error: GatewayRequestException) {
                // The typed exception would discard the pairing; the code must win.
                assertEquals("proof_expired", error.code)
            }
        }

    @Test
    fun `unauthenticated calls never retry a proof rejection`() =
        runTest {
            val serverNow = Instant.now().plusSeconds(SKEW_SECONDS)
            val captured = mutableListOf<CapturedRequest>()
            val repo =
                GatewayRepositoryImpl(
                    HttpClient(
                        engineOf(captured, listOf(Stub(HttpStatusCode.Unauthorized, proofExpiredBody(serverNow)))),
                    ),
                    json,
                )

            try {
                repo.startPairing("https", HOST)
                fail("expected GatewayRequestException")
            } catch (error: GatewayRequestException) {
                assertEquals(401, error.statusCode)
            }
            assertEquals(1, captured.size)
            assertNull(captured[0].proofTimestamp)
        }

    private fun proofExpiredBody(serverTime: Instant) =
        """{"error":"gateway credential proof expired","code":"proof_expired","serverTime":"${rfc3339(serverTime)}"}"""

    private fun statusBody(serverTime: Instant) =
        """{"name":"gw","version":"1.0","protocolVersion":"v3",""" +
            """"serverTime":"${rfc3339(serverTime)}","remote":{"configured":false}}"""

    private fun rfc3339(instant: Instant) =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    private fun httpDate(instant: Instant) =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC))

    private fun CapturedRequest.timestampSeconds(): Long? = proofTimestamp?.toLong()

    private fun assertNear(
        expectedSeconds: Long,
        actualSeconds: Long?,
        toleranceSeconds: Long,
    ) {
        assertTrue(
            "expected a proof timestamp within ${toleranceSeconds}s of $expectedSeconds but was $actualSeconds",
            actualSeconds != null && abs(expectedSeconds - actualSeconds) <= toleranceSeconds,
        )
    }

    private companion object {
        private const val HOST = "gw.local:5788"
        private const val OTHER_HOST = "elsewhere.local:5788"
        private const val SKEW_SECONDS = 37L * 60 + 13
    }
}
