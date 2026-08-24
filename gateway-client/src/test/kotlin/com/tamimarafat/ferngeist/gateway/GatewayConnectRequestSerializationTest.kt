package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayConnectRequestSerializationTest {
    private val json =
        Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

    @Test
    fun `fresh flag serializes as new true`() {
        assertEquals(
            """{"sessionMode":"resilient","new":true}""",
            json.encodeToString(
                GatewayConnectRequest(sessionMode = "resilient", new = true),
            ),
        )
    }

    @Test
    fun `absent fields are omitted`() {
        assertEquals(
            """{"sessionMode":"resilient"}""",
            json.encodeToString(GatewayConnectRequest(sessionMode = "resilient")),
        )
        assertEquals("""{}""", json.encodeToString(GatewayConnectRequest()))
    }
}
