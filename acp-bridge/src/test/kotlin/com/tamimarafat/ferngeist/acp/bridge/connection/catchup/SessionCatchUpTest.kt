package com.tamimarafat.ferngeist.acp.bridge.connection.catchup

import com.tamimarafat.ferngeist.acp.bridge.connection.FailingSendTransport
import com.tamimarafat.ferngeist.acp.bridge.connection.ScriptedTransport
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionGateway
import com.tamimarafat.ferngeist.acp.bridge.connection.clientOver
import com.tamimarafat.ferngeist.acp.bridge.connection.installSdkClient
import com.tamimarafat.ferngeist.acp.bridge.connection.newGateway
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.CatchUpCursor
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionRuntime
import com.tamimarafat.ferngeist.acp.bridge.session.persist.restore
import com.tamimarafat.ferngeist.acp.bridge.session.persist.savedState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reattaching over the gateway's catch-up resume and its exactly-once prompts, pinned at the wire. */
class SessionCatchUpTest {
    private fun chunk(
        text: String,
        seq: Long,
    ) = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":""" +
        """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$text"},""" +
        """"_meta":{"ferngeist/seq":$seq}}}}"""

    private fun promptEcho(
        text: String,
        clientId: String,
        seq: Long,
    ) = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":""" +
        """{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"$text"},""" +
        """"_meta":{"ferngeist/seq":$seq,"ferngeist/clientId":"$clientId"}}}}"""

    private fun catchUpReply(
        turnActive: Boolean,
        gap: Boolean,
        result: String = "",
    ) = """{"jsonrpc":"2.0","id":${'$'}ID,"result":{$result"_meta":{"ferngeist/catchUp":""" +
        """{"epoch":"e1","headSeq":5,"turnActive":$turnActive,"gap":$gap}}}}"""

    /** A chat restored after process death mid-turn: the reply so far is update 4 of agent process `e1`. */
    private suspend fun restoredMidTurn(): SessionRuntime {
        val runtime = SessionRuntime(sessionId = "s1")
        runtime.catchUpFrom("e1")
        runtime.onLocalPromptStarted("go", emptyList(), emptyList())
        runtime.onEvent(AppSessionEvent.AgentMessage("hel"), updateSeq = 4)
        return SessionRuntime(sessionId = "s1").apply { restore(runtime.savedState()) }
    }

    private fun SessionBridge.replyText(): String =
        snapshot.value.messages
            .last()
            .segments
            .joinToString("") { it.text }

    private fun attachOver(
        transport: ScriptedTransport,
        runtime: SessionRuntime? = null,
    ): SessionGateway =
        newGateway(runtime).also {
            installSdkClient(
                it,
                clientOver(transport).also { client ->
                    client.protocol.start()
                },
            )
        }

    @Test
    fun `a restored mid-turn chat catches up from its saved update and keeps streaming`() =
        runTest {
            val transport =
                ScriptedTransport(mapOf("session/resume" to listOf(chunk("lo", 5), catchUpReply(true, false))))
            val gateway = attachOver(transport, restoredMidTurn())

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

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
            val gateway = attachOver(transport, restoredMidTurn())

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

            assertEquals("hello", bridge.replyText())
            assertFalse(bridge.snapshot.value.isStreaming)
        }

    @Test
    fun `a turn result replayed ahead of the catch-up reply settles the turn with its outcome and duration`() =
        runTest {
            val gateway = newGateway(restoredMidTurn())
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

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

            val snapshot = bridge.snapshot.value
            assertFalse(snapshot.isStreaming)
            assertEquals(TurnStatus.FINISHED, snapshot.turnStatus)
            val turn = snapshot.messages.last().turn!!
            assertEquals(4200L, turn.finishedAt - turn.startedAt)
        }

    @Test
    fun `only a gapless catch-up shows the gateway still refuses repeat prompts`() =
        runTest {
            val restored = restoredMidTurn()
            assertNull("a restored chat has yet to learn it", restored.snapshot.value.dropsRepeatPrompts)
            val gapless = ScriptedTransport(mapOf("session/resume" to listOf(catchUpReply(false, false))))
            val heldBridge =
                attachOver(gapless, restored).attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1")
                    as SessionBridge

            // A gap means the runtime that ran the chat's prompts may be gone, and its ids with it.
            val gap =
                ScriptedTransport(
                    mapOf("session/resume" to listOf(catchUpReply(false, true)), "session/load" to listOf(LOADED)),
                )
            val refilledBridge =
                attachOver(gap, restoredMidTurn()).attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1")
                    as SessionBridge

            assertEquals(true, heldBridge.snapshot.value.dropsRepeatPrompts)
            assertEquals(false, refilledBridge.snapshot.value.dropsRepeatPrompts)
        }

    @Test
    fun `only a gap the agent cannot refill reports a hole in the history`() =
        runTest {
            val resumeOnly = ScriptedTransport(mapOf("session/resume" to listOf(catchUpReply(false, true))))
            val holedGateway = attachOver(resumeOnly, restoredMidTurn())
            val holed = holedGateway.attachSession(SessionAttachRpc.RESUME, "s1", "/w", epoch = "e1") as SessionBridge
            val refill =
                ScriptedTransport(
                    mapOf("session/resume" to listOf(catchUpReply(false, true)), "session/load" to listOf(LOADED)),
                )
            val refilled =
                attachOver(refill, restoredMidTurn()).attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1")
                    as SessionBridge

            assertTrue(holed.snapshot.value.historyGap)
            assertFalse(refilled.snapshot.value.historyGap)
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
            val gateway = attachOver(transport, restoredMidTurn())

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

            assertEquals(listOf("session/resume", "session/load"), transport.sentMethods)
            assertEquals("replayed", bridge.replyText())
            assertEquals(SessionLoadState.READY, bridge.snapshot.value.loadState)
            assertEquals(CatchUpCursor("e1", 1), bridge.runtime.catchUpFrom(null))
        }

    @Test
    fun `a turn that ends during a gap refill still stops streaming`() =
        runTest {
            val gateway = newGateway(restoredMidTurn())
            val turnResult =
                """{"jsonrpc":"2.0","method":"_ferngeist/turn_result","params":""" +
                    """{"sessionId":"s1","stopReason":"end_turn","usage":null,"durationMs":null}}"""
            val transport =
                ScriptedTransport(
                    mapOf(
                        "session/resume" to listOf(catchUpReply(true, true)),
                        "session/load" to
                            listOf(chunk("replayed", 1), turnResult, """{"jsonrpc":"2.0","id":${'$'}ID,"result":{}}"""),
                    ),
                )
            val client = clientOver(transport)
            client.protocol.onRemoteTurnEnded(gateway::endRemoteTurn)
            installSdkClient(gateway, client.also { it.protocol.start() })

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

            assertFalse(bridge.snapshot.value.isStreaming)
            assertEquals(TurnStatus.FINISHED, bridge.snapshot.value.turnStatus)
        }

    @Test
    fun `a turn result replayed ahead of a gap refill still ends the turn`() =
        runTest {
            val gateway = newGateway(restoredMidTurn())
            val turnResult =
                """{"jsonrpc":"2.0","method":"_ferngeist/turn_result","params":""" +
                    """{"sessionId":"s1","stopReason":"end_turn","usage":null,"durationMs":4200}}"""
            val transport =
                ScriptedTransport(
                    mapOf(
                        "session/resume" to listOf(turnResult, catchUpReply(true, true)),
                        "session/load" to listOf(chunk("replayed", 1), LOADED),
                    ),
                )
            val client = clientOver(transport)
            client.protocol.onRemoteTurnEnded(gateway::endRemoteTurn)
            installSdkClient(gateway, client.also { it.protocol.start() })

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

            assertFalse(bridge.snapshot.value.isStreaming)
            assertEquals(TurnStatus.FINISHED, bridge.snapshot.value.turnStatus)
        }

    @Test
    fun `a turn still running behind a gap refill keeps the session busy`() =
        runTest {
            val transport =
                ScriptedTransport(
                    mapOf(
                        "session/resume" to listOf(catchUpReply(true, true)),
                        "session/load" to listOf(chunk("replayed", 1), LOADED),
                    ),
                )
            val gateway = attachOver(transport, restoredMidTurn())

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

            assertEquals("replayed", bridge.replyText())
            assertTrue(bridge.snapshot.value.isStreaming)
            assertEquals(TurnStatus.WORKING, bridge.snapshot.value.turnStatus)
        }

    @Test
    fun `a gap refill keeps the modes the catch-up resume reported`() =
        runTest {
            val modes = """"modes":{"currentModeId":"code","availableModes":[{"id":"code","name":"Code"}]},"""
            val transport =
                ScriptedTransport(
                    mapOf(
                        "session/resume" to listOf(catchUpReply(false, true, result = modes)),
                        "session/load" to listOf(chunk("replayed", 1), LOADED),
                    ),
                )
            val gateway = attachOver(transport, restoredMidTurn())

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

            assertTrue(
                bridge.snapshot.value.configOptions
                    .isNotEmpty(),
            )
        }

    @Test
    fun `a turn result replayed ahead of a plain load reply ends the loaded turn`() =
        runTest {
            val gateway = newGateway()
            val turnResult =
                """{"jsonrpc":"2.0","method":"_ferngeist/turn_result","params":""" +
                    """{"sessionId":"s1","stopReason":"refusal","usage":null,"durationMs":4200}}"""
            val transport =
                ScriptedTransport(mapOf("session/load" to listOf(chunk("replayed", 1), turnResult, LOADED)))
            val client = clientOver(transport)
            client.protocol.onRemoteTurnEnded(gateway::endRemoteTurn)
            installSdkClient(gateway, client.also { it.protocol.start() })

            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w") as SessionBridge

            assertEquals("replayed", bridge.replyText())
            assertFalse(bridge.snapshot.value.isStreaming)
            assertEquals(TurnStatus.ERROR, bridge.snapshot.value.turnStatus)
        }

    @Test
    fun `a saved state from another agent process skips the catch-up`() =
        runTest {
            val gateway = newGateway(restoredMidTurn())
            val transport = FailingSendTransport(IllegalStateException("transport boom"))
            installSdkClient(gateway, clientOver(transport))

            runCatching { gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e2") }

            assertEquals(listOf("session/load"), transport.sentMethods)
        }

    @Test
    fun `the new-chat placeholder never reaches the agent as a session id`() =
        runTest {
            val gateway = newGateway()
            val transport = FailingSendTransport(IllegalStateException("transport boom"))
            installSdkClient(gateway, clientOver(transport))

            val result = runCatching { gateway.attachSession(SessionAttachRpc.LOAD, NEW_SESSION_ARG, "/w") }

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(emptyList<String>(), transport.sentMethods)
        }

    @Test
    fun `a catch-up replaying a prompt the chat already holds shows it once`() =
        runTest {
            // Saved right after the prompt went out, before the gateway's number for it arrived.
            val runtime = SessionRuntime(sessionId = "s1").apply { catchUpFrom("e1") }
            runtime.onLocalPromptStarted("go", emptyList(), emptyList(), clientId = "c1")
            val transport =
                ScriptedTransport(
                    mapOf(
                        "session/resume" to
                            listOf(promptEcho("go", "c1", 1), chunk("hel", 2), catchUpReply(true, false)),
                    ),
                )
            val gateway = attachOver(transport, runtime)

            val bridge =
                gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w", epoch = "e1") as SessionBridge

            val users =
                bridge.snapshot.value.messages
                    .filter { it.role == ChatMessage.Role.USER }
            assertEquals(listOf("go"), users.map { it.content })
            assertTrue("the gateway's numbered echo proves delivery", users.single().deliveryConfirmed)
            assertEquals("hel", bridge.replyText())
        }

    @Test
    fun `a prompt names itself on the wire and a resend the gateway already delivered counts as delivered`() =
        runTest {
            val delivered =
                """{"jsonrpc":"2.0","id":${'$'}ID,"error":{"code":-32050,"message":"prompt already delivered",""" +
                    """"data":{"clientId":"c1"}}}"""
            val transport =
                ScriptedTransport(
                    mapOf(
                        "session/load" to listOf("""{"jsonrpc":"2.0","id":${'$'}ID,"result":{}}"""),
                        "session/prompt" to listOf(delivered),
                    ),
                )
            val gateway = attachOver(transport)
            val bridge = gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/w") as SessionBridge

            bridge.runtime.onLocalPromptStarted("go", emptyList(), emptyList(), clientId = "c1")
            gateway.sendSessionMessage("s1", "go", clientId = "c1")

            val meta = (transport.sentParams.last() as JsonObject)["_meta"] as JsonObject
            assertEquals("c1", meta["ferngeist/clientId"]?.jsonPrimitive?.content)
            assertFalse("no failed turn", bridge.snapshot.value.isStreaming)
            val prompt =
                bridge.snapshot.value.messages
                    .single { it.role == ChatMessage.Role.USER }
            assertTrue(prompt.deliveryConfirmed)
            assertTrue(bridge.events.replayCache.none { it is AppSessionEvent.TurnComplete })
        }

    private companion object {
        const val LOADED = """{"jsonrpc":"2.0","id":${'$'}ID,"result":{}}"""
    }
}
