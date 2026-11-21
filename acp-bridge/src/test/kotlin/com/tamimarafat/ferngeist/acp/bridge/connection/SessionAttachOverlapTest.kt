package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertSame
import org.junit.Test

/** Two attaches of one session at once, answered by hand on the wire. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionAttachOverlapTest {
    private val transport = ScriptedTransport(emptyMap())
    private val answered = mutableSetOf<JsonRpcRequest>()

    private fun requests(method: String) =
        transport.sentMessages.filterIsInstance<JsonRpcRequest>().filter { it.method.name == method && it !in answered }

    private fun answerNext(
        method: String,
        body: String,
    ) {
        val request = requests(method).first()
        answered += request
        transport.receive("""{"jsonrpc":"2.0","id":${ACPJson.encodeToJsonElement(request.id)},$body}""")
    }

    /** Waits for [done]; the SDK answers the wire on its own handler thread, outside the test scheduler. */
    private fun TestScope.settle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + REAL_WORK_TIMEOUT_MS
        while (!done() && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(REAL_WORK_POLL_MS)
        }
        check(done()) { "SDK handler work did not finish in time" }
    }

    /**
     * A reconnect's restore and a chat's own load of one session overlap, and the restore fails.
     * Its teardown must not strand the chat's attach on the bridge it closed.
     */
    @Test
    fun `an attach overlapping a failing attach of the same session still attaches`() =
        runTest {
            val gateway = newGateway()
            val bridge = SessionBridge("s1", null)
            (gatewayField(gateway, "sessionRegistry") as AcpSessionRegistry).storeBridge("s1", bridge)
            // A bridge following the gateway's numbering, so each attach first tries a catch-up resume.
            bridge.runtime.catchUpFrom("e1")
            installSdkClient(gateway, clientOver(transport).also { it.protocol.start() })

            val restore = async { runCatching { gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w") } }
            runCurrent()
            val chat = async { runCatching { gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w") } }
            runCurrent()
            answerNext("session/resume", REFUSED)
            settle { requests("session/load").isNotEmpty() }
            answerNext("session/load", REFUSED)
            settle { restore.isCompleted }
            settle {
                if (requests("session/resume").isNotEmpty()) answerNext("session/resume", REFUSED)
                if (requests("session/load").isNotEmpty()) answerNext("session/load", """"result":{}""")
                chat.isCompleted
            }

            assertSame(
                "the chat's attach must leave its session live",
                chat.await().getOrThrow(),
                gateway.getSession("s1"),
            )
        }

    private companion object {
        const val REFUSED = """"error":{"code":-32603,"message":"refused"}"""
        const val REAL_WORK_TIMEOUT_MS = 2_000L
        const val REAL_WORK_POLL_MS = 5L
    }
}
