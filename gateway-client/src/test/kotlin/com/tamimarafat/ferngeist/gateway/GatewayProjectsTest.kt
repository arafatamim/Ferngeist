package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjects
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProjectsTest {
    @Test
    fun `agent line arrives before checkouts and response completion`() =
        runTest {
            val body = ByteChannel(autoFlush = true)
            val received = CompletableDeferred<GatewayProjectLine.Agent>()
            val engine =
                MockEngine { request ->
                    assertEquals("/v1/projects", request.url.encodedPath)
                    assertEquals("claude,codex", request.url.parameters["agents"])
                    assertEquals("Bearer token", request.headers["Authorization"])
                    respond(body, HttpStatusCode.OK)
                }
            val client = HttpClient(engine)
            val projects = GatewayProjects(client, Json { ignoreUnknownKeys = true })
            val lines = mutableListOf<GatewayProjectLine>()
            val collecting =
                launch {
                    projects
                        .fetchProjects("http", "gateway:5788", "token", listOf("claude", "codex"))
                        .collect { line ->
                            lines += line
                            if (line is GatewayProjectLine.Agent) received.complete(line)
                        }
                }
            body.writeStringUtf8(
                """{"type":"agent","agentId":"claude","status":"ok","future":true,""" +
                    """"chats":[{"sessionId":"s","cwd":"/r","repo":"/r","updatedAt":"2026-10-24T10:00:00Z"}]}""" + "\n",
            )
            val agent = received.await()
            assertEquals("s", agent.chats.single().sessionId)
            assertTrue(collecting.isActive)
            body.writeStringUtf8(
                """{"type":"checkouts","repos":[{"repo":"/r","branch":"main","worktrees":[]}]}""" + "\n",
            )
            body.close()
            collecting.join()
            assertEquals("main", (lines.last() as GatewayProjectLine.Checkouts).repos.single().branch)
            client.close()
        }
}
