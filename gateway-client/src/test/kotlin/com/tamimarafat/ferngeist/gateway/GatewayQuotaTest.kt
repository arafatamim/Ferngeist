package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.repository.CodexBarNotFoundException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayQuotaTest {
    @Test
    fun `missing CodexBar is distinguishable from other gateway failures`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"error":"quotas unavailable","code":"codexbar_not_found"}""",
                        HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val repo = GatewayRepositoryImpl(HttpClient(engine), Json { ignoreUnknownKeys = true })
            val error = runCatching { repo.fetchQuotas("http", "gw.local:5788", "") }.exceptionOrNull()
            assertTrue(error is CodexBarNotFoundException)
        }

    @Test
    fun `credit usage applies to any provider and requires a positive total`() =
        runTest {
            for ((used, total, expected) in listOf(
                Triple("5.17", "5.00", 1f),
                Triple("2.50", "5.00", 0.5f),
                Triple("0.00", "0.00", null),
            )) {
                val engine =
                    MockEngine {
                        respond(
                            """{"providers":[{"provider":"another-provider","usage":{"details":[{"title":"Credits","rows":[{"label":"Used","value":"\u0024$used"},{"label":"Total added","value":"\u0024$total"}]}]}}]}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val repo = GatewayRepositoryImpl(HttpClient(engine), Json { ignoreUnknownKeys = true })
                val quota = repo.fetchQuotas("http", "gw.local:5788", "").single()
                assertEquals(expected, quota.windows.singleOrNull()?.usedFraction)
            }
        }

    @Test
    fun `OpenRouter credits provide a balance summary`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"providers":[{"provider":"openrouter","usage":{"details":[{"title":"Credits","rows":[{"label":"Remaining","value":"\u00240.00"},{"label":"Used","value":"\u00245.17"}]}]}}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val repo = GatewayRepositoryImpl(HttpClient(engine), Json { ignoreUnknownKeys = true })
            val quota = repo.fetchQuotas("http", "gw.local:5788", "").single()
            assertEquals("Remaining · \u00240.00", quota.balance)
            assertEquals("Credits", quota.billing.single().title)
            assertEquals(
                listOf("Remaining" to "\u00240.00", "Used" to "\u00245.17"),
                quota.billing
                    .single()
                    .rows
                    .map { it.label to it.value },
            )
        }

    @Test
    fun `provider currency balance is retained`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"providers":[{"provider":"grok","usage":{"providerCost":{"balance":14.46,"currencyCode":"USD"}}}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val repo = GatewayRepositoryImpl(HttpClient(engine), Json { ignoreUnknownKeys = true })
            val quota = repo.fetchQuotas("http", "gw.local:5788", "").single()
            assertEquals(14.46, quota.providerBalance!!.amount, 0.001)
            assertEquals("USD", quota.providerBalance!!.currency)
        }

    @Test
    fun `DeepSeek balance is not presented as percentage usage`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"providers":[{"provider":"deepseek","rateWindowLabels":{"primary":"Balance"},"usage":{"primary":{"usedPercent":0,"resetDescription":"\u00247.53 (Paid: \u00247.53 / Granted: \u00240.00)"}}}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val repo = GatewayRepositoryImpl(HttpClient(engine), Json { ignoreUnknownKeys = true })
            val quota = repo.fetchQuotas("http", "gw.local:5788", "").single()
            assertEquals(emptyList<Any>(), quota.windows)
            assertEquals("\u00247.53 (Paid: \u00247.53 / Granted: \u00240.00)", quota.balance)
        }

    @Test
    fun `quota endpoint maps provider windows`() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/v1/quotas", request.url.encodedPath)
                    respond(
                        """{"providers":[{"provider":"codex","displayName":"Codex","icon":"data:image/svg+xml;base64,PHN2Zy8+","rateWindowLabels":{"primary":"Five hours"},"usage":{"primary":{"usedPercent":40,"windowMinutes":300,"resetsAt":"2026-10-31T12:00:00Z"},"secondary":null,"tertiary":null,"updatedAt":"2026-10-31T10:00:00Z"}}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val repo = GatewayRepositoryImpl(HttpClient(engine), Json { ignoreUnknownKeys = true })
            val quota = repo.fetchQuotas("http", "gw.local:5788", "").single()
            assertEquals("codex", quota.provider)
            assertEquals("Codex", quota.displayName)
            assertEquals("data:image/svg+xml;base64,PHN2Zy8+", quota.icon)
            assertEquals("Five hours", quota.windows.single().label)
            assertEquals(0.4f, quota.windows.single().usedFraction, 0.001f)
            assertEquals(0.6f, quota.windows.single().paceFraction!!, 0.001f)
        }
}
