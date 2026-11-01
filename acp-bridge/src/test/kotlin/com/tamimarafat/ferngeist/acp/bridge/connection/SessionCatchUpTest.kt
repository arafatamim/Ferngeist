package com.tamimarafat.ferngeist.acp.bridge.connection

import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.CatchUpCursor
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionRuntime
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reattaching over the gateway's catch-up resume, pinned at the wire. */
class SessionCatchUpTest {
    private fun chunk(
        text: String,
        seq: Long,
    ) = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":""" +
        """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$text"},""" +
        """"_meta":{"ferngeist/seq":$seq}}}}"""

    private fun catchUpReply(
        turnActive: Boolean,
        gap: Boolean,
    ) = """{"jsonrpc":"2.0","id":${'$'}ID,"result":{"_meta":{"ferngeist/catchUp":""" +
        """{"epoch":"e1","headSeq":5,"turnActive":$turnActive,"gap":$gap}}}}"""

    /** The state a chat saved mid-turn: the reply so far is update 4 of agent process `e1`. */
    private suspend fun savedMidTurn(): String {
        val runtime = SessionRuntime(sessionId = "s1")
        runtime.catchUpFrom("e1")
        runtime.onLocalPromptStarted("go", emptyList(), emptyList())
        runtime.onEvent(AppSessionEvent.AgentMessage("hel"), updateSeq = 4)
        return runtime.savedState()
    }

    private fun SessionBridge.replyText(): String =
        snapshot.value.messages
            .last()
            .segments
            .joinToString("") { it.text }

    private fun attachOver(transport: ScriptedTransport): SessionGateway =
        newGateway().also { installSdkClient(it, clientOver(transport).also { client -> client.protocol.start() }) }

    @Test
    fun `a restored mid-turn chat catches up from its saved update and keeps streaming`() =
        runTest {
            val transport =
                ScriptedTransport(mapOf("session/resume" to listOf(chunk("lo", 5), catchUpReply(true, false))))
            val gateway = attachOver(transport)

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", savedMidTurn(), "e1") as SessionBridge

            assertEquals("only the missed update is fetched; no load", listOf("session/resume"), transport.sentMethods)
            assertEquals("hello", bridge.replyText())
            assertTrue("the turn still runs on the gateway", bridge.snapshot.value.isStreaming)
            assertEquals(CatchUpCursor("e1", 5), bridge.runtime.catchUpFrom(null))
        }

    @Test
    fun `a turn that ended while the app was dead stops streaming after the catch-up`() =
        runTest {
            val transport =
                ScriptedTransport(mapOf("session/resume" to listOf(chunk("lo", 5), catchUpReply(false, false))))
            val gateway = attachOver(transport)

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", savedMidTurn(), "e1") as SessionBridge

            assertEquals("hello", bridge.replyText())
            assertFalse(bridge.snapshot.value.isStreaming)
        }

    @Test
    fun `a turn result replayed ahead of the catch-up reply settles the turn with its outcome and duration`() =
        runTest {
            val gateway = newGateway()
            val turnResult =
                """{"jsonrpc":"2.0","method":"_ferngeist/turn_result","params":""" +
                    """{"sessionId":"s1","stopReason":"end_turn","usage":null,"durationMs":4200}}"""
            val transport =
                ScriptedTransport(
                    mapOf("session/resume" to listOf(chunk("lo", 5), turnResult, catchUpReply(false, false))),
                )
            val client = clientOver(transport)
            client.protocol.onRemoteTurnEnded(gateway::endRemoteTurn)
            installSdkClient(gateway, client.also { it.protocol.start() })

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", savedMidTurn(), "e1") as SessionBridge

            val snapshot = bridge.snapshot.value
            assertFalse(snapshot.isStreaming)
            assertEquals(TurnStatus.FINISHED, snapshot.turnStatus)
            val turn = snapshot.messages.last().turn!!
            assertEquals(4200L, turn.finishedAt - turn.startedAt)
        }

    @Test
    fun `a gap is refilled by a load over the restored chat`() =
        runTest {
            val loadReply = """{"jsonrpc":"2.0","id":${'$'}ID,"result":{}}"""
            val transport =
                ScriptedTransport(
                    mapOf(
                        "session/resume" to listOf(catchUpReply(false, true)),
                        "session/load" to listOf(chunk("replayed", 1), loadReply),
                    ),
                )
            val gateway = attachOver(transport)

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", savedMidTurn(), "e1") as SessionBridge

            assertEquals(listOf("session/resume", "session/load"), transport.sentMethods)
            assertEquals("replayed", bridge.replyText())
            assertEquals(SessionLoadState.READY, bridge.snapshot.value.loadState)
            assertEquals(CatchUpCursor("e1", 1), bridge.runtime.catchUpFrom(null))
        }

    @Test
    fun `a saved state from another agent process skips the catch-up`() =
        runTest {
            val gateway = newGateway()
            val transport = FailingSendTransport(IllegalStateException("transport boom"))
            installSdkClient(gateway, clientOver(transport))

            runCatching { gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", savedMidTurn(), "e2") }

            assertEquals(listOf("session/load"), transport.sentMethods)
        }
}
