package com.tamimarafat.ferngeist.acp.bridge.connection

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
}
