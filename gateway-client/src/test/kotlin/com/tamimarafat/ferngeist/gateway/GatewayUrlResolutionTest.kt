package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayUrlResolutionTest {
    private val gatewaySource =
        GatewaySource(
            id = "gw-1",
            name = "Test Gateway",
            scheme = "http",
            host = "192.168.1.100:8080",
            gatewayCredential = "test-cred",
        )

    private val gatewaySourceTls = gatewaySource.copy(scheme = "https", host = "my-gateway.example.com")

    private fun handoff(websocketUrl: String) =
        GatewayConnectResponse(
            runtimeId = "rt-1",
            sessionId = "sess-1",
            attachToken = "attach-1",
            webSocketUrl = websocketUrl,
        )

    @Test
    fun `uses advertised URL when host is routable`() {
        assertEquals(
            "ws://192.168.1.100:8080/v1/runtimes/rt-1/ws",
            resolveGatewayWebSocketUrl(
                gatewaySource,
                handoff("ws://192.168.1.100:8080/v1/runtimes/rt-1/ws"),
            ),
        )
        assertEquals(
            "ws://my-gateway.example.com:8080/v1/runtimes/rt-1/ws",
            resolveGatewayWebSocketUrl(
                gatewaySource,
                handoff("ws://my-gateway.example.com:8080/v1/runtimes/rt-1/ws"),
            ),
        )
    }

    @Test
    fun `falls back to gateway host when advertised URL has unroutable host`() {
        listOf("0.0.0.0", "127.0.0.1", "localhost", "::1").forEach { advertisedHost ->
            assertEquals(
                "ws://192.168.1.100:8080/v1/runtimes/rt-1/ws",
                resolveGatewayWebSocketUrl(
                    gatewaySource,
                    handoff("ws://$advertisedHost:8080/v1/runtimes/rt-1/ws"),
                ),
            )
        }
    }

    @Test
    fun `uses wss scheme when gateway scheme is https`() {
        assertEquals(
            "wss://my-gateway.example.com/v1/runtimes/rt-1/ws",
            resolveGatewayWebSocketUrl(
                gatewaySourceTls,
                handoff("ws://0.0.0.0/v1/runtimes/rt-1/ws"),
            ),
        )
    }
}
