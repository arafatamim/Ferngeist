package com.tamimarafat.ferngeist.acp.bridge.connection

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpTransportClientTest {
    @Test
    fun `resilient config detects session mode`() {
        val config =
            AcpConnectionConfig(
                host = "192.168.1.5:5788",
                sessionId = "sess-42",
                attachToken = "at-1",
            )
        assertTrue(config.isResilientSession)
    }

    @Test
    fun `non-resilient config returns false`() {
        val config =
            AcpConnectionConfig(
                host = "192.168.1.5:5788",
            )
        assertFalse(config.isResilientSession)
    }

    @Test
    fun `resilient config isResilient is true even without attachToken`() {
        val config =
            AcpConnectionConfig(
                host = "192.168.1.5:5788",
                sessionId = "sess-42",
            )
        assertTrue(config.isResilientSession)
    }

    @Test
    fun `gateway connection detected by credential`() {
        val config =
            AcpConnectionConfig(
                host = "192.168.1.5:5788",
                gatewayCredential = "cred-1",
                sessionId = "sess-42",
            )
        assertTrue(config.isGatewayConnection)
    }

    @Test
    fun `manual server is not a gateway connection`() {
        val config =
            AcpConnectionConfig(
                host = "192.168.1.5:5788",
            )
        assertFalse(config.isGatewayConnection)
    }

    @Test
    fun `gateway connection without sessionId is the doomed-handshake case`() {
        // The gateway returned a session-less connect descriptor: a gateway connection
        // (credential present) but no sessionId. The transport must recognise this and
        // fail fast instead of connecting to the resilient endpoint without params.
        val config =
            AcpConnectionConfig(
                host = "192.168.1.5:5788",
                gatewayCredential = "cred-1",
                sessionId = null,
            )
        assertTrue(config.isGatewayConnection)
        assertFalse(config.isResilientSession)
    }

    // ---- Reconnect backoff ----

    @Test
    fun `backoff is exponential and capped at 30s`() {
        val expectedBase = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        expectedBase.forEachIndexed { index, base ->
            val attempt = index + 1
            val delay = computeReconnectDelayMs(attempt)
            // Full jitter: [0.5, 1.5) of the base, then capped at 30s.
            val min = (base * 0.5).toLong()
            val max = minOf((base * 1.5).toLong(), 30_000L) + 1L
            assertTrue(
                "attempt $attempt delay $delay outside [$min, $max)",
                delay in min until max,
            )
        }
    }

    @Test
    fun `backoff never exceeds 30s even for huge attempt counts`() {
        listOf(10, 20, 100, 10_000).forEach { attempt ->
            val delay = computeReconnectDelayMs(attempt)
            assertTrue("attempt $attempt delay $delay exceeds cap", delay <= 30_000L)
            assertTrue("attempt $attempt delay $delay must stay positive", delay > 0L)
        }
    }

    // ---- Diagnostics telemetry ----

    @Test
    fun `setReconnectAttempt records attempts and dedupes`() = runTest {
        val store = AcpDiagnosticsStore()
        assertEquals(0, store.diagnostics.value.reconnectAttempts)

        store.setReconnectAttempt(1)
        assertEquals(1, store.diagnostics.value.reconnectAttempts)

        // Same value -> no redundant update.
        store.setReconnectAttempt(1)
        val updatedAtAfterDedupe = store.diagnostics.value.lastUpdatedAtMs
        store.setReconnectAttempt(2)
        assertEquals(2, store.diagnostics.value.reconnectAttempts)
        assertTrue(store.diagnostics.value.lastUpdatedAtMs >= updatedAtAfterDedupe)
    }

    @Test
    fun `setReconnectAttempt ignores non-positive values`() = runTest {
        val store = AcpDiagnosticsStore()
        store.setReconnectAttempt(0)
        store.setReconnectAttempt(-1)
        assertEquals(0, store.diagnostics.value.reconnectAttempts)
    }

    @Test
    fun `markDisconnected and startConnect reset reconnect attempts`() = runTest {
        val store = AcpDiagnosticsStore()
        store.setReconnectAttempt(5)
        assertEquals(5, store.diagnostics.value.reconnectAttempts)

        store.markDisconnected()
        assertEquals(0, store.diagnostics.value.reconnectAttempts)

        store.setReconnectAttempt(3)
        store.startConnect("ws://example.com")
        assertEquals(0, store.diagnostics.value.reconnectAttempts)
    }
}
