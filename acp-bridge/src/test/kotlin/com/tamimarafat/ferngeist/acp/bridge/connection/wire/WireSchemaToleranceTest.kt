package com.tamimarafat.ferngeist.acp.bridge.connection.wire

import com.tamimarafat.ferngeist.acp.bridge.connection.ScriptedTransport
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.connection.clientOver
import com.tamimarafat.ferngeist.acp.bridge.connection.installSdkClient
import com.tamimarafat.ferngeist.acp.bridge.connection.newGateway
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.core.model.text
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A newer agent's frames, as raw JSON through the SDK: what this client does not know must not stop what it does. */
class WireSchemaToleranceTest {
    private fun update(body: String) =
        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$body}}"""

    private suspend fun loadOver(vararg frames: String): SessionBridge {
        val transport = ScriptedTransport(mapOf("session/load" to frames.toList()))
        val gateway = newGateway().also { installSdkClient(it, clientOver(transport).also { c -> c.protocol.start() }) }
        return gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w") as SessionBridge
    }

    private fun SessionBridge.replyText(): String =
        snapshot.value.messages
            .last()
            .segments
            .joinToString("") { it.text }

    @Test
    fun `unknown fields on an update, its content and the load reply are ignored`() =
        runTest {
            val bridge =
                loadOver(
                    update(
                        """{"sessionUpdate":"agent_message_chunk","futureField":[1],""" +
                            """"content":{"type":"text","text":"hel","futureField":{"x":1}}}""",
                    ),
                    update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}"""),
                    """{"jsonrpc":"2.0","id":${'$'}ID,"result":{"futureResult":true}}""",
                )

            assertEquals("hello", bridge.replyText())
        }

    @Test
    fun `an unknown update variant is skipped and later known updates still land`() =
        runTest {
            val bridge =
                loadOver(
                    update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hel"}}"""),
                    update("""{"sessionUpdate":"future_update","payload":{"x":1}}"""),
                    update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"future_block","x":1}}"""),
                    update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}"""),
                    """{"jsonrpc":"2.0","id":${'$'}ID,"result":{}}""",
                )

            assertEquals("hello", bridge.replyText())
        }
}
