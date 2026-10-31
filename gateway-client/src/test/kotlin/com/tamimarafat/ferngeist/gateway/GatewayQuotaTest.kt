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
import org.junit.Test

class GatewayQuotaTest {
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
