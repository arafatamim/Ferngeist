package com.tamimarafat.ferngeist.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayPairingPayloadParserTest {

    @Test
    fun `parse URI format with all fields`() {
        val payload = GatewayPairingPayloadParser.parse(
            "ferngeist-gateway://pair?host=myhost.local&code=abc123&challengeId=ch1"
        )
        assertNotNull(payload)
        assertEquals("myhost.local", payload!!.host)
        assertEquals("abc123", payload.code)
        assertEquals("ch1", payload.challengeId)
        assertEquals("http", payload.scheme)
    }

    @Test
    fun `parse URI format with explicit scheme`() {
        val payload = GatewayPairingPayloadParser.parse(
            "ferngeist-gateway://pair?host=myhost.local&code=abc123&challengeId=ch1&scheme=https"
        )
        assertNotNull(payload)
        assertEquals("https", payload!!.scheme)
    }

    @Test
    fun `parse JSON format`() {
        val json = """{"host":"myhost.local","code":"abc123","challengeId":"ch1"}"""
        val payload = GatewayPairingPayloadParser.parse(json)
        assertNotNull(payload)
        assertEquals("myhost.local", payload!!.host)
        assertEquals("abc123", payload.code)
        assertEquals("ch1", payload.challengeId)
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(GatewayPairingPayloadParser.parse(""))
        assertNull(GatewayPairingPayloadParser.parse("   "))
    }

    @Test
    fun `returns null for missing required fields`() {
        val payload = GatewayPairingPayloadParser.parse(
            "ferngeist-gateway://pair?host=myhost.local&code=abc123"
        )
        assertNull(payload)
    }
}
