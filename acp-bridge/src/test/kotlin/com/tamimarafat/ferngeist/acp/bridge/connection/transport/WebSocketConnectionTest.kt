package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

class WebSocketConnectionTest {
    @Test
    fun `unresolvable hosts fail as network errors`() {
        val error =
            assertThrows(IOException::class.java) {
                runBlocking {
                    withTimeout(10_000) {
                        sharedWebSocketClient.webSocketSession("ws://startup-probe.invalid:5788")
                    }
                }
            }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is UnresolvedAddressException })
    }
}
