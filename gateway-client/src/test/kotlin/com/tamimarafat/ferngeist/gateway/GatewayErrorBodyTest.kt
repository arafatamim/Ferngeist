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
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayErrorBodyTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun repoReturning(
        body: String,
        status: HttpStatusCode,
    ): GatewayRepositoryImpl {
        val engine =
            MockEngine {
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return GatewayRepositoryImpl(HttpClient(engine), json)
    }

    @Test
    fun `carries the raw body on the exception`() =
        runTest {
            val repo = repoReturning("""{"error":"custom agent limit reached (50)"}""", HttpStatusCode.Conflict)
            val error =
                runCatching { repo.fetchAgents("http", "gw.local:5788", "token") }
                    .exceptionOrNull() as GatewayRequestException
            assertEquals(409, error.statusCode)
            assertEquals("""{"error":"custom agent limit reached (50)"}""", error.responseBody)
            assertEquals("custom agent limit reached (50)", gatewayErrorMessage(error.responseBody))
        }

    @Test
    fun `returns null for a body that is not the error envelope`() {
        assertNull(gatewayErrorMessage("<html>gateway down</html>"))
        assertNull(gatewayErrorMessage(""))
        assertNull(gatewayErrorMessage(null))
    }
}
