package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStream
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Runs against the real OkHttp engine: MockEngine cannot speak a WebSocket upgrade. */
class GatewayHttpClientTest {
    private val client = gatewayHttpClient()
    private val server = ServerSocket(0)
    private val upgradeRequests = AtomicInteger()

    @After
    fun tearDown() {
        server.close()
        client.close()
    }

    @Test
    fun `unresolvable hosts fail as network errors`() {
        assertThrows(IOException::class.java) {
            runBlocking { withTimeout(TIMEOUT_MS) { client.webSocketSession("ws://startup-probe.invalid:5788") } }
        }
    }

    @Test
    fun `a refused turn stream upgrade keeps its status, so an old gateway's 404 is not retried`() {
        refuseUpgradesWith("404 Not Found")

        val error = assertThrows(GatewayRequestException::class.java) { collectTurnStream() }

        assertEquals(404, error.statusCode)
        assertEquals(1, upgradeRequests.get())
    }

    @Test
    fun `an unauthorized turn stream upgrade is re-signed once`() {
        refuseUpgradesWith("401 Unauthorized")

        val error = assertThrows(GatewayRequestException::class.java) { collectTurnStream() }

        assertEquals(401, error.statusCode)
        assertEquals(2, upgradeRequests.get())
    }

    private fun collectTurnStream() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                GatewayTurnStream(client, Json, GatewayClock())
                    .frames("http", "127.0.0.1:${server.localPort}", "token")
                    .collect {}
            }
        }

    /** Answers every request on every connection with [status], keeping connections alive. */
    private fun refuseUpgradesWith(status: String) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val out = it.getOutputStream()
                        while (true) {
                            val headers = generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }
                            if (headers.toList().isEmpty()) break
                            upgradeRequests.incrementAndGet()
                            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
