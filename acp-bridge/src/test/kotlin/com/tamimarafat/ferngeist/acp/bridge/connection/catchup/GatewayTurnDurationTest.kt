package com.tamimarafat.ferngeist.acp.bridge.connection.catchup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayTurnDurationTest {
    @Test
    fun reads_the_gateways_duration_and_ignores_anything_else() {
        fun read(meta: String?) = gatewayTurnDurationMs(meta?.let(Json::parseToJsonElement))

        assertEquals(120_000L, read("""{"ferngeist":{"durationMs":120000},"agent":{"x":1}}"""))
        assertEquals(null, read(null))
        assertEquals(null, read("""{"agent":{"durationMs":5}}"""))
        assertEquals(null, read("""{"ferngeist":{"durationMs":"soon"}}"""))
        assertEquals(null, read("""{"ferngeist":5}"""))
    }
}
