package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.gateway.turns.GatewayTurns
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayTurnsTest {
    @Test
    fun `turns are read through the signed pipeline and keep every reported fact`() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/v1/turns", request.url.encodedPath)
                    assertEquals("Bearer token", request.headers["Authorization"])
                    respond(
                        """
                        {"gatewayStartedAt":"2026-10-27T09:00:00Z","observedAt":"2026-10-27T09:01:00Z","turns":[
                        {"agentId":"claude","sessionId":"ses_a","gatewaySessionId":"gs_1","runtimeId":"rt_1",
                         "state":"running","attention":["permission"],"startedAt":"2026-10-27T09:00:30Z",
                         "changedAt":"2026-10-27T09:00:30Z"},
                        {"agentId":"claude","sessionId":"ses_b","gatewaySessionId":"gs_2","runtimeId":"rt_2",
                         "state":"failed","attention":[],"startedAt":"2026-10-27T08:00:00Z",
                         "changedAt":"2026-10-27T08:01:00Z","stopReason":"refusal"}]}
                        """.trimIndent(),
                        HttpStatusCode.OK,
                    )
                }
            val client = HttpClient(engine)
            val turns = GatewayTurns(client, Json { ignoreUnknownKeys = true })

            val snapshot = turns.fetchTurns("http", "gateway:5788", "token")

            assertEquals("2026-10-27T09:00:00Z", snapshot.gatewayStartedAt)
            assertEquals("2026-10-27T09:01:00Z", snapshot.observedAt)
            val running = snapshot.turns.first()
            assertEquals("gs_1", running.gatewaySessionId)
            assertEquals("rt_1", running.runtimeId)
            assertEquals("running", running.state)
            assertEquals(listOf("permission"), running.attention)
            assertEquals("2026-10-27T09:00:30Z", running.changedAt)
            assertNull(running.stopReason)
            val failed = snapshot.turns.last()
            assertEquals("failed", failed.state)
            assertEquals("refusal", failed.stopReason)
            client.close()
        }

    @Test
    fun `an empty snapshot parses and an older gateway surfaces its 404`() =
        runTest {
            val empty =
                MockEngine { respond("""{"gatewayStartedAt":"g","observedAt":"o","turns":[]}""", HttpStatusCode.OK) }
            val client = HttpClient(empty)
            val turns = GatewayTurns(client, Json { ignoreUnknownKeys = true })
            assertEquals(emptyList<String>(), turns.fetchTurns("http", "g:1", "token").turns.map { it.state })
            client.close()

            val olderClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
            val older = GatewayTurns(olderClient, Json { ignoreUnknownKeys = true })
            val failure = runCatching { older.fetchTurns("http", "g:1", "token") }.exceptionOrNull()
            assertEquals(404, (failure as GatewayRequestException).statusCode)
            olderClient.close()
        }
}
