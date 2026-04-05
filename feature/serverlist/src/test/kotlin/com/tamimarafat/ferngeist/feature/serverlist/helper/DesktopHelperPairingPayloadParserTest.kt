package com.tamimarafat.ferngeist.feature.serverlist.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopHelperPairingPayloadParserTest {

    @Test
    fun parse_uriPayload_returnsChallengeMetadata() {
        val payload = DesktopHelperPairingPayloadParser.parse(
            "ferngeist-helper://pair?scheme=https&host=helper.example.com&challengeId=abc123&code=ZXCVBN",
        )

        requireNotNull(payload)
        assertEquals("https", payload.scheme)
        assertEquals("helper.example.com", payload.host)
        assertEquals("abc123", payload.challengeId)
        assertEquals("ZXCVBN", payload.code)
    }

    @Test
    fun parse_jsonPayload_returnsChallengeMetadata() {
        val payload = DesktopHelperPairingPayloadParser.parse(
            """
            {
              "scheme": "http",
              "host": "192.168.1.42:5788",
              "challengeId": "pair-123",
              "code": "ABCDEF"
            }
            """.trimIndent(),
        )

        requireNotNull(payload)
        assertEquals("http", payload.scheme)
        assertEquals("192.168.1.42:5788", payload.host)
        assertEquals("pair-123", payload.challengeId)
        assertEquals("ABCDEF", payload.code)
    }

    @Test
    fun parse_missingChallengeId_returnsNull() {
        val payload = DesktopHelperPairingPayloadParser.parse(
            "ferngeist-helper://pair?scheme=http&host=192.168.1.42:5788&code=ABCDEF",
        )

        assertNull(payload)
    }
}
