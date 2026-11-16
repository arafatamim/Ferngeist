package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.Implementation
import com.tamimarafat.ferngeist.acp.bridge.connection.ScriptedTransport
import com.tamimarafat.ferngeist.acp.bridge.connection.clientOver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(UnstableApi::class)
class AuthStatusCapabilityTransportTest {
    @Test
    fun `claude initialize reply with object auth status decodes and keeps the rest`() =
        runTest {
            // Shape sent by claude-agent-acp >= 0.104.0.
            val reply =
                """{"jsonrpc":"2.0","id":${'$'}ID,"result":{"protocolVersion":1,""" +
                    """"agentCapabilities":{"loadSession":true,"auth":{"logout":{},"status":{}}},""" +
                    """"authMethods":[{"id":"claude-login","name":"Log in with Claude"}]}}"""
            val transport = AuthStatusCapabilityTransport(ScriptedTransport(mapOf("initialize" to listOf(reply))))
            val client = clientOver(transport).also { it.protocol.start() }

            val info =
                client.initialize(
                    ClientInfo(
                        capabilities = ClientCapabilities(),
                        implementation = Implementation(name = "Ferngeist", version = "1.0.0"),
                    ),
                )

            val auth = requireNotNull(info.capabilities.auth)
            assertNotNull(auth.logout)
            assertNull(auth.status)
            assertEquals(true, info.capabilities.loadSession)
            assertEquals(listOf("claude-login"), info.authMethods.map { it.id.toString() })
        }
}
