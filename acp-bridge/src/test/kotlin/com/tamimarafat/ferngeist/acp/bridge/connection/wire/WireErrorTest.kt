package com.tamimarafat.ferngeist.acp.bridge.connection.wire

import com.agentclientprotocol.protocol.JsonRpcException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpSessionRegistry
import com.tamimarafat.ferngeist.acp.bridge.connection.ScriptedTransport
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.connection.clientOver
import com.tamimarafat.ferngeist.acp.bridge.connection.gatewayField
import com.tamimarafat.ferngeist.acp.bridge.connection.installSdkClient
import com.tamimarafat.ferngeist.acp.bridge.connection.newGateway
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Agent error responses as raw JSON-RPC through the SDK. */
class WireErrorTest {
    @Test
    fun `attachSession exposes the code and data of a wire error response`() =
        runTest {
            val gateway = newGateway()
            val registry = gatewayField(gateway, "sessionRegistry") as AcpSessionRegistry
            registry.storeBridge("s1", SessionBridge("s1", null))
            val transport =
                ScriptedTransport(
                    mapOf(
                        "session/load" to
                            listOf(
                                """{"jsonrpc":"2.0","id":${'$'}ID,"error":{""" +
                                    """"code":-32042,"message":"Invalid cwd","data":{"path":"/missing"}}}""",
                            ),
                    ),
                )
            installSdkClient(gateway, clientOver(transport).also { it.protocol.start() })

            val failure =
                runCatching { gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/missing") }
                    .exceptionOrNull() as JsonRpcException

            assertEquals(-32042, failure.code)
            assertEquals("Invalid cwd", failure.message)
            assertEquals("""{"path":"/missing"}""", failure.data.toString())
            assertNull(registry.getBridge("s1"))
        }
}
