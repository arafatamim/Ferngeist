package com.tamimarafat.ferngeist.acp.bridge.connection.wire

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.tamimarafat.ferngeist.acp.bridge.connection.ScriptedTransport
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionGateway
import com.tamimarafat.ferngeist.acp.bridge.connection.clientOver
import com.tamimarafat.ferngeist.acp.bridge.connection.installSdkClient
import com.tamimarafat.ferngeist.acp.bridge.connection.newGateway
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session elicitations as an agent sends them: raw `elicitation/create` in, the client's JSON-RPC reply out. */
class WireElicitationTest {
    private val transport =
        ScriptedTransport(mapOf("session/load" to listOf("""{"jsonrpc":"2.0","id":${'$'}ID,"result":{}}""")))

    private suspend fun attached(): Pair<SessionGateway, SessionBridge> {
        val gateway = newGateway().also { installSdkClient(it, clientOver(transport).also { c -> c.protocol.start() }) }
        return gateway to gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w") as SessionBridge
    }

    private fun formRequest(id: Int) =
        """{"jsonrpc":"2.0","id":$id,"method":"elicitation/create","params":{"sessionId":"s1","mode":"form",""" +
            """"message":"Name?","requestedSchema":{"type":"object","properties":""" +
            """{"name":{"type":"string","title":"Name"}},"required":["name"]}}}"""

    private fun reply(id: Int): JsonRpcSuccessResponse? =
        transport.sentMessages
            .filterIsInstance<JsonRpcSuccessResponse>()
            .singleOrNull { ACPJson.encodeToJsonElement(it.id).toString() == "$id" }

    private fun replyTo(id: Int): String {
        awaitReal { reply(id) != null }
        return reply(id)!!.result.toString()
    }

    private fun SessionBridge.pending(): List<ChatElicitationRequest> = snapshot.value.pendingElicitations

    /** Waits on [count] sheets; the SDK runs the agent's requests on its own handler thread. */
    private fun SessionBridge.awaitPending(count: Int): List<ChatElicitationRequest> {
        awaitReal { pending().size == count }
        return pending()
    }

    private fun awaitReal(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + REAL_WORK_TIMEOUT_MS
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(REAL_WORK_POLL_MS)
        check(done()) { "SDK handler work did not finish in time" }
    }

    @Test
    fun `form answers reach the agent as accept, decline and cancel on their own request ids`() =
        runTest {
            val (gateway, bridge) = attached()
            listOf(7, 8, 9).forEach { transport.receive(formRequest(it)) }
            val (accept, decline, cancel) = bridge.awaitPending(3).map { it.key }

            gateway.submitElicitation("s1", accept, mapOf("name" to ChatElicitationValue.TextValue("Ada")))
            gateway.declineElicitation("s1", decline)
            gateway.cancelElicitation("s1", cancel)

            assertEquals("""{"action":"accept","content":{"name":"Ada"}}""", replyTo(7))
            assertEquals("""{"action":"decline"}""", replyTo(8))
            assertEquals("""{"action":"cancel"}""", replyTo(9))
            assertTrue("every sheet is dismissed", bridge.awaitPending(0).isEmpty())
        }

    @Test
    fun `an accepted URL elicitation is cleared by the agent's completion`() =
        runTest {
            val (gateway, bridge) = attached()
            transport.receive(
                """{"jsonrpc":"2.0","id":7,"method":"elicitation/create","params":{"sessionId":"s1","mode":"url",""" +
                    """"message":"Sign in","elicitationId":"el-1","url":"https://example.com/auth"}}""",
            )
            val url = bridge.awaitPending(1).single() as ChatElicitationRequest.Url
            assertEquals("https://example.com/auth", url.url)
            // A second, still-open URL elicitation must survive the first one's completion.
            transport.receive(
                """{"jsonrpc":"2.0","id":8,"method":"elicitation/create","params":{"sessionId":"s1","mode":"url",""" +
                    """"message":"Approve","elicitationId":"el-2","url":"https://example.com/approve"}}""",
            )

            bridge.awaitPending(2)
            gateway.submitElicitation("s1", url.key, emptyMap())
            // The SDK routes a completion only once its elicitation's acceptance has gone out.
            replyTo(7)
            transport.receive("""{"jsonrpc":"2.0","method":"elicitation/complete","params":{"elicitationId":"el-1"}}""")

            assertEquals("""{"action":"accept","content":{}}""", replyTo(7))
            val open = bridge.awaitPending(1).single() as ChatElicitationRequest.Url
            assertEquals("el-2", open.elicitationId)
        }

    @Test
    fun `cancelling the turn sends session cancel, then answers a pending elicitation cancel`() =
        runTest {
            val (gateway, bridge) = attached()
            transport.receive(formRequest(7))
            bridge.awaitPending(1)

            gateway.cancelSession("s1")
            val answer = replyTo(7)

            // The first message sent is the session/load that attached the chat.
            val after = transport.sentMessages.drop(1).map(::describe)
            assertEquals(listOf("notification session/cancel", "reply 7"), after)
            assertEquals("""{"action":"cancel"}""", answer)
            assertTrue(bridge.pending().isEmpty())
        }

    @Test
    fun `a dropped connection clears a pending sheet without answering on the dead wire`() =
        runTest {
            val (gateway, bridge) = attached()
            transport.receive(formRequest(7))
            val key = bridge.awaitPending(1).single().key
            val sentBefore = transport.sentMessages.size

            transport.drop()
            // What the connection does on a transport reset: detach, keeping the bridge.
            gateway.detachAllSessions()
            // A sheet submitted after the drop must not reach a later connection.
            gateway.submitElicitation("s1", key, mapOf("name" to ChatElicitationValue.TextValue("Ada")))

            assertTrue("the sheet is dismissed", bridge.awaitPending(0).isEmpty())
            assertEquals("nothing is sent after the drop", sentBefore, transport.sentMessages.size)
        }

    private fun describe(message: JsonRpcMessage): String =
        when (message) {
            is JsonRpcNotification -> "notification ${message.method.name}"
            is JsonRpcSuccessResponse -> "reply ${ACPJson.encodeToJsonElement(message.id)}"
            else -> message.toString()
        }
}

private const val REAL_WORK_TIMEOUT_MS = 5_000L
private const val REAL_WORK_POLL_MS = 5L
