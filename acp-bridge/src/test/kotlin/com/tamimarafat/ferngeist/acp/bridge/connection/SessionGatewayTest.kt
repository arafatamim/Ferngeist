package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SetSessionConfigOptionResponse
import com.agentclientprotocol.model.SetSessionModeResponse
import com.agentclientprotocol.model.SetSessionModelResponse
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.Transport
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.completeHydration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class SessionGatewayTest {
    private class ConnectivityStub : ConnectivityObserver {
        private val _isConnected = MutableStateFlow(true)
        override val isConnected: Flow<Boolean> = _isConnected
    }

    /**
     * The SDK's [ClientSession] is an interface and this module has no mocking library,
     * so the members a prompt turn touches are hand-rolled and the rest are stubs — the
     * same shape [AcpSessionRegistryTest] uses. Each [prompt] call hands out the next
     * scripted turn flow, so one fake can serve a multi-turn test.
     */
    private class FakeClientSession(
        private val turns: List<Flow<Event>>,
        val cancelGate: CompletableDeferred<Unit>? = null,
        private val cancelError: Throwable? = null,
    ) : ClientSession {
        private var turnIndex = 0

        /** Completed as soon as [cancel] is entered, so a test can order against the RPC. */
        val cancelStarted = CompletableDeferred<Unit>()

        override val sessionId: SessionId get() = error("unused")
        override val parameters: SessionCreationParameters get() = error("unused")
        override val client: Client get() = error("unused")
        override val operations: ClientSessionOperations get() = error("unused")

        // ClientSession requires a suspending RPC that returns the event stream.
        @Suppress("SuspendFunWithFlowReturnType")
        override suspend fun prompt(
            content: List<ContentBlock>,
            _meta: JsonElement?,
        ): Flow<Event> {
            val turn = turns[turnIndex.coerceAtMost(turns.lastIndex)]
            turnIndex++
            return turn
        }

        override suspend fun cancel() {
            cancelStarted.complete(Unit)
            cancelGate?.await()
            cancelError?.let { throw it }
        }

        override suspend fun close(_meta: JsonElement?): CloseSessionResponse = error("unused")

        override val modesSupported: Boolean get() = false
        override val availableModes: List<SessionMode> get() = emptyList()
        override val currentMode: StateFlow<SessionModeId> get() = error("unused")

        override suspend fun setMode(
            modeId: SessionModeId,
            _meta: JsonElement?,
        ): SetSessionModeResponse = error("unused")

        override val modelsSupported: Boolean get() = false
        override val availableModels: List<ModelInfo> get() = emptyList()
        override val currentModel: StateFlow<ModelId> get() = error("unused")

        override suspend fun setModel(
            modelId: ModelId,
            _meta: JsonElement?,
        ): SetSessionModelResponse = error("unused")

        override val configOptionsSupported: Boolean get() = false
        override val configOptions: StateFlow<List<SessionConfigOption>> get() = error("unused")

        override suspend fun setConfigOption(
            configId: SessionConfigId,
            value: SessionConfigOptionValue,
            _meta: JsonElement?,
        ): SetSessionConfigOptionResponse = error("unused")
    }

    private fun newGateway(): SessionGateway {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val connection =
            AcpConnection(
                connectivityObserver = ConnectivityStub(),
                resumeAttachToken = { _, _ -> error("no gateway resume in this test") },
                scope = scope,
            )
        return SessionGateway(
            connection = connection,
            permissionFlow = PermissionFlow(),
            elicitationFlow = ElicitationFlow(),
            bridgeFactory = { sessionId -> SessionBridge(sessionId, null) },
            scope = scope,
        )
    }

    /**
     * The gateway builds its [AcpSessionRegistry] internally and exposes no injection
     * seam, so tests reach the private field reflectively — the same approach
     * [com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHubTest] uses for `_connectionState`.
     */
    private fun gatewayField(
        gateway: SessionGateway,
        name: String,
    ): Any {
        val field = SessionGateway::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(gateway)!!
    }

    /** Registers [session] and a fresh bridge for [sessionId]; returns the bridge. */
    private fun installSession(
        gateway: SessionGateway,
        sessionId: String,
        session: ClientSession,
    ): SessionBridge {
        val bridge = SessionBridge(sessionId, null)
        val registry = gatewayField(gateway, "sessionRegistry") as AcpSessionRegistry
        registry.storeBridge(sessionId, bridge)
        registry.storeSdkSession(sessionId, session)
        return bridge
    }

    /**
     * Registers a bridge for [sessionId] without an SDK session. Only this shape reaches
     * `attachSession`'s real `session/load` path: an SDK session in the registry makes it
     * treat the session as already loaded and return early.
     */
    private fun registeredBridge(
        gateway: SessionGateway,
        sessionId: String,
    ): SessionBridge? = (gatewayField(gateway, "sessionRegistry") as AcpSessionRegistry).getBridge(sessionId)

    private fun installBridge(
        gateway: SessionGateway,
        sessionId: String,
    ): SessionBridge {
        val bridge = SessionBridge(sessionId, null)
        (gatewayField(gateway, "sessionRegistry") as AcpSessionRegistry).storeBridge(sessionId, bridge)
        return bridge
    }

    private fun permissionFlowOf(gateway: SessionGateway): PermissionFlow =
        gatewayField(gateway, "permissionFlow") as PermissionFlow

    /**
     * Installs an SDK client on the connection's socket owner. Its client property
     * is `private set` and only assigned by a real connect, so a test that needs
     * `attachSession` past its disconnected check has to set it reflectively.
     */
    private fun installSdkClient(
        gateway: SessionGateway,
        client: Client,
    ) {
        val connection = gatewayField(gateway, "connection")
        val transportField = connection.javaClass.getDeclaredField("transportClient")
        transportField.isAccessible = true
        val transport = transportField.get(connection)!!
        val socketField = transport.javaClass.getDeclaredField("socket")
        socketField.isAccessible = true
        val socket = socketField.get(transport)!!
        val clientField = socket.javaClass.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(socket, client)
    }

    /**
     * A [Client] whose next outbound request is failed by [transport].
     *
     * The SDK's [Client] is a final class — it can be neither proxied nor subclassed — and
     * this module has no mocking library. Its collaborator [Transport] is an interface
     * though, so the failure is injected at the wire instead: a real client over a real
     * protocol whose transport refuses every message. Only requests routed through it fail.
     */
    private fun clientOver(transport: Transport): Client =
        Client(
            Protocol(
                parentScope = CoroutineScope(UnconfinedTestDispatcher()),
                transport = transport,
                options = ProtocolOptions(protocolDebugName = "SessionGatewayTest"),
            ),
        )

    /** A [Client] whose every outbound request fails with [error]. */
    private fun clientFailingSessionLoad(error: Throwable): Client = clientOver(FailingSendTransport(error))

    /** A [Transport] whose every outbound message fails with [error]. */
    private class FailingSendTransport(
        private val error: Throwable,
    ) : Transport {
        /** Methods of the requests sent through this transport, in order. */
        val sentMethods = mutableListOf<String>()

        override val state: StateFlow<Transport.State> = MutableStateFlow(Transport.State.STARTED)

        override fun start() = Unit

        override fun send(frame: TransportFrame) {
            val message = (frame as? TransportFrame.Single)?.message
            sentMethods += (message as? JsonRpcRequest)?.method?.name ?: frame.toString()
            throw error
        }

        override fun onFrame(handler: (TransportFrame) -> Unit) = Unit

        override fun onError(handler: (Throwable) -> Unit) = Unit

        override fun onClose(handler: () -> Unit) = Unit

        override fun close() = Unit
    }

    private fun turnReasons(bridge: SessionBridge): List<String> =
        bridge.events.replayCache
            .filterIsInstance<AppSessionEvent.TurnComplete>()
            .map { it.stopReason }

    private fun responseTurn(): Flow<Event> =
        flowOf(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))

    @Test
    fun `createSession with blank cwd returns null`() =
        runTest {
            val gateway = newGateway()
            assertNull(gateway.createSession(""))
            assertNull(gateway.createSession("   "))
        }

    @Test
    fun `createSession while disconnected throws AcpDisconnectedException`() =
        runTest {
            val gateway = newGateway()
            val result = runCatching { gateway.createSession("/some/cwd") }
            assertTrue(result.exceptionOrNull() is AcpDisconnectedException)
        }

    @Test
    fun `attachSession rethrows cancellation instead of clearing the session`() =
        runTest {
            val gateway = newGateway()
            val bridge = installBridge(gateway, "s1")
            installSdkClient(gateway, clientFailingSessionLoad(CancellationException("screen closed")))

            val result = runCatching { gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/some/cwd") }

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertSame("cancellation must not tear the session down", bridge, registeredBridge(gateway, "s1"))
            assertEquals(SessionLoadState.HYDRATING, bridge.snapshot.value.loadState)
        }

    @Test
    fun `attachSession still clears the session on a real attach failure`() =
        runTest {
            val gateway = newGateway()
            installBridge(gateway, "s1")
            installSdkClient(gateway, clientFailingSessionLoad(IllegalStateException("transport boom")))

            val result = runCatching { gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/some/cwd") }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertNull("a real load failure must clear the session", registeredBridge(gateway, "s1"))
        }

    @Test
    fun `attachSession clears the session when the attach deadline expires`() =
        runTest {
            val gateway = newGateway()
            val bridge = installBridge(gateway, "s1")
            // The exception has an internal constructor, so produce a real one the way the
            // production path does: the deadline on the caller's `withTimeout` fires.
            val timedOut = runCatching { withTimeout(1) { delay(1_000) } }.exceptionOrNull()!!
            installSdkClient(gateway, clientFailingSessionLoad(timedOut))

            val result = runCatching { gateway.attachSession(SessionAttachRpc.LOAD, "s1", "/some/cwd") }

            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            assertNull(
                "a timed-out load must not leave the session registered and HYDRATING",
                registeredBridge(gateway, "s1"),
            )
            assertEquals(SessionLoadState.FAILED, bridge.snapshot.value.loadState)
        }

    @Test
    fun `attachSession with Resume sends session resume rather than session load`() =
        runTest {
            val gateway = newGateway()
            installBridge(gateway, "s1")
            val transport = FailingSendTransport(IllegalStateException("transport boom"))
            installSdkClient(gateway, clientOver(transport))

            val result = runCatching { gateway.attachSession(SessionAttachRpc.RESUME, "s1", "/some/cwd") }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(
                "a resume-only agent must be sent session/resume, never session/load",
                listOf("session/resume"),
                transport.sentMethods,
            )
        }

    /**
     * The harness injects failures at the transport's `send()`, so the SDK wraps them and a
     * wire-level JSON-RPC error response never reaches the caller as a [JsonRpcException].
     * This pins the end-to-end guarantee that does survive here — a failed attach relays the
     * agent's own message rather than any invented diagnosis.
     */
    @Test
    fun `attachSession relays the agent's own failure message`() =
        runTest {
            val gateway = newGateway()
            installBridge(gateway, "s1")
            installSdkClient(
                gateway,
                clientFailingSessionLoad(
                    JsonRpcException(
                        code = JsonRpcErrorCode.INVALID_PARAMS.code,
                        message = "Invalid params: cwd does not exist",
                        data = JsonNull,
                    ),
                ),
            )

            val result = runCatching { gateway.attachSession(SessionAttachRpc.RESUME, "s1", "/some/cwd") }

            val message = result.exceptionOrNull()?.message
            assertTrue(
                "the agent's own message must reach the caller: $message",
                message?.contains("cwd does not exist") == true,
            )
            assertNull(registeredBridge(gateway, "s1"))
        }

    @Test
    fun `a turn the gateway reports ended on an earlier connection stops streaming`() =
        runTest {
            val gateway = newGateway()
            val bridge = installBridge(gateway, "s1")
            // Chunks of a turn sent before the reconnect: the reply that would end it went to the old socket.
            bridge.emitEvent(AppSessionEvent.AgentMessage("still going"))
            assertTrue(bridge.snapshot.value.isStreaming)

            gateway.endRemoteTurn("s1", "end_turn")

            val snapshot = bridge.snapshot.value
            assertFalse(snapshot.isStreaming)
            assertTrue(snapshot.messages.none { it.isStreaming })
        }

    @Test
    fun `a gateway-resumed replay hydrates through the normal path without dedup`() =
        runTest {
            val gateway = newGateway()
            val bridge = installBridge(gateway, "s1")

            // The agent refused the load as already loaded, so the gateway restores the
            // session itself and announces the replay before the buffered update frames.
            gateway.onRemoteResumed("s1", replayedFrames = 2)

            assertEquals(
                "the announced replay must buffer instead of overwriting live state",
                SessionLoadState.HYDRATING,
                bridge.snapshot.value.loadState,
            )

            // The buffered session/update frames arrive as ordinary events...
            bridge.emitEvent(AppSessionEvent.AgentMessage("first"))
            bridge.emitEvent(AppSessionEvent.AgentMessage("second"))
            // ...and the session/load response commits the replay: `completeHydration`
            // swaps the buffered state in, then `SessionLoadComplete` settles the reply
            // (folds the chunk segments and derives their flat content), exactly as
            // [SessionGateway.attachAndHydrate] does.
            bridge.completeHydration()
            bridge.emitEvent(AppSessionEvent.SessionLoadComplete)

            assertEquals(SessionLoadState.READY, bridge.snapshot.value.loadState)
            assertEquals(
                "both replayed frames must land, in order, with nothing dropped",
                listOf("first", "second"),
                bridge.events.replayCache
                    .filterIsInstance<AppSessionEvent.AgentMessage>()
                    .map { it.text },
            )
            assertEquals(
                "the hydrated transcript must carry the whole replay",
                listOf("firstsecond"),
                bridge.snapshot.value.messages
                    .map { it.content },
            )
        }

    @Test
    fun `a transport reset keeps the bridge but hides it until it is attached again`() =
        runTest {
            val gateway = newGateway()
            val bridge = installSession(gateway, "s1", FakeClientSession(listOf(emptyFlow())))

            gateway.detachAllSessions()

            assertNull("a detached session must not pass for a live one", gateway.getSession("s1"))
            assertSame("the next attach reuses this bridge and its transcript", bridge, registeredBridge(gateway, "s1"))
        }

    @Test
    fun `a turn interrupted by a transport reset ends and fails its live caller`() =
        runTest {
            val gateway = newGateway()
            val collecting = CompletableDeferred<Unit>()
            val bridge =
                installSession(
                    gateway,
                    "s1",
                    FakeClientSession(
                        listOf(
                            flow {
                                collecting.complete(Unit)
                                awaitCancellation()
                            },
                        ),
                    ),
                )

            val send = async { runCatching { gateway.sendSessionMessage("s1", "hello") } }
            collecting.await()
            gateway.detachAllSessions()

            // A CancellationException here would silently end the caller's coroutine.
            assertTrue(send.await().exceptionOrNull() is AcpDisconnectedException)
            assertEquals(
                "the turn must end without claiming the success no reply established",
                listOf("cancelled"),
                turnReasons(bridge),
            )
        }

    @Test
    fun `a prompt stream that ends without a response emits no turn end of its own`() =
        runTest {
            val gateway = newGateway()
            val turn = Channel<Event>(Channel.UNLIMITED)
            val collecting = CompletableDeferred<Unit>()
            val bridge =
                installSession(
                    gateway,
                    "s1",
                    FakeClientSession(
                        listOf(
                            flow {
                                collecting.complete(Unit)
                                turn.receiveAsFlow().collect { emit(it) }
                            },
                            responseTurn(),
                        ),
                    ),
                )

            // The turn runs on the bridge's own scope (Dispatchers.Default), so the
            // handshake — not a yield — is what orders the cancel after collection.
            val first = launch { gateway.sendSessionMessage("s1", "first") }
            collecting.await()
            gateway.cancelSession("s1") // STOP pressed mid-turn
            turn.close() // stream ends without a terminal event
            first.join()

            gateway.sendSessionMessage("s1", "second")

            assertEquals(
                "a stream that ends without a terminal response must not be papered over " +
                    "with a synthetic turn end: only the second turn's real response counts",
                listOf("end_turn"),
                turnReasons(bridge),
            )
        }

    @Test
    fun `a failing cancel propagates to the caller`() =
        runTest {
            val gateway = newGateway()
            val failure =
                JsonRpcException(
                    code = JsonRpcErrorCode.METHOD_NOT_FOUND.code,
                    message = "Method not found",
                    data = JsonNull,
                )
            installSession(
                gateway,
                "s1",
                FakeClientSession(emptyList(), cancelError = failure),
            )

            val result = runCatching { gateway.cancelSession("s1") }

            // Swallowing this used to leave the UI reporting a cancelled turn the agent never
            // received, and made both of the facade's cancel-failure branches unreachable.
            assertSame(failure, result.exceptionOrNull())
        }

    @Test
    fun `a failed cancel does not mark the active turn cancelled`() =
        runTest {
            val gateway = newGateway()
            val collecting = CompletableDeferred<Unit>()
            val endTurn = CompletableDeferred<Unit>()
            val failure = IllegalStateException("cancel rejected")
            val session =
                FakeClientSession(
                    turns =
                        listOf(
                            flow {
                                collecting.complete(Unit)
                                endTurn.await()
                                error("prompt connection closed")
                            },
                        ),
                    cancelError = failure,
                )
            val bridge = installSession(gateway, "s1", session)
            val send = async { runCatching { gateway.sendSessionMessage("s1", "hello") } }
            collecting.await()

            assertSame(failure, runCatching { gateway.cancelSession("s1") }.exceptionOrNull())
            endTurn.complete(Unit)
            assertTrue(send.await().isFailure)

            assertEquals(listOf("error"), turnReasons(bridge))
        }

    @Test
    fun `a failing prompt turn still ends the turn before rethrowing`() =
        runTest {
            val gateway = newGateway()
            val failure = IllegalStateException("transport died mid-turn")
            val bridge =
                installSession(
                    gateway,
                    "s1",
                    FakeClientSession(listOf(flow { throw failure })),
                )

            val result = runCatching { gateway.sendSessionMessage("s1", "hello") }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            // The turn runs on the bridge scope, so its starter may already be gone and
            // no rollback would run: the turn must end here or the bubble streams forever.
            assertEquals(listOf("error"), turnReasons(bridge))
        }

    @Test
    fun `a failing turn after a cancel reports cancelled`() =
        runTest {
            val gateway = newGateway()
            val turn = Channel<Event>(Channel.UNLIMITED)
            val collecting = CompletableDeferred<Unit>()
            val failsAfterClose =
                flow {
                    collecting.complete(Unit)
                    turn.receiveAsFlow().collect { emit(it) }
                    error("aborted")
                }
            val bridge =
                installSession(gateway, "s1", FakeClientSession(listOf(failsAfterClose)))

            val first = launch { runCatching { gateway.sendSessionMessage("s1", "hello") } }
            collecting.await()
            gateway.cancelSession("s1")
            turn.close()
            first.join()

            assertEquals(listOf("cancelled"), turnReasons(bridge))
        }

    @Test
    fun `a cancel after the turn ended does not mislabel the next turn`() =
        runTest {
            val gateway = newGateway()
            val bridge =
                installSession(
                    gateway,
                    "s1",
                    FakeClientSession(
                        listOf(
                            responseTurn(),
                            // A stream that dies without a terminal response is the only
                            // shape that reads the cancel mark, so a stale mark would
                            // surface here as "cancelled".
                            flow { throw IllegalStateException("turn aborted") },
                        ),
                    ),
                )

            // Turn 1 ends normally, with no response expected after it.
            gateway.sendSessionMessage("s1", "first")
            // A cancel lands after the turn is over (user tapped STOP late).
            gateway.cancelSession("s1")
            // Turn 2 must not inherit that stale mark.
            runCatching { gateway.sendSessionMessage("s1", "second") }

            assertEquals(listOf("end_turn", "error"), turnReasons(bridge))
        }

    @Test
    fun `a slow cancel RPC still labels the turn it interrupted`() =
        runTest {
            val gateway = newGateway()
            val turn = Channel<Event>(Channel.UNLIMITED)
            val collecting = CompletableDeferred<Unit>()
            val session =
                FakeClientSession(
                    turns =
                        listOf(
                            flow {
                                collecting.complete(Unit)
                                turn.receiveAsFlow().collect { emit(it) }
                                error("aborted")
                            },
                        ),
                    // The RPC does not return until the test releases it, so the turn
                    // stream dies while the cancel is still in flight.
                    cancelGate = CompletableDeferred(),
                )
            val bridge = installSession(gateway, "s1", session)

            val first = launch { runCatching { gateway.sendSessionMessage("s1", "first") } }
            collecting.await()
            val cancelling = launch { gateway.cancelSession("s1") }
            session.cancelStarted.await()
            turn.close() // the turn dies before session.cancel() returns
            first.join()

            // Marking before the RPC is what makes this the cancelled turn; marking after
            // it would report end_turn here.
            assertEquals(listOf("cancelled"), turnReasons(bridge))

            session.cancelGate?.complete(Unit) // now let the RPC return
            cancelling.join()
        }

    @Test
    fun `cancelSession resolves pending permissions for that session only`() =
        runTest {
            val gateway = newGateway()
            val bridge = installSession(gateway, "s1", FakeClientSession(listOf(emptyFlow())))
            val pending = CompletableDeferred<RequestPermissionOutcome>()
            val otherSession = CompletableDeferred<RequestPermissionOutcome>()
            val permissionFlow = permissionFlowOf(gateway)
            permissionFlow.addPending("tc1", "s1", pending)
            permissionFlow.addPending("tc2", "s2", otherSession)

            gateway.cancelSession("s1")

            assertEquals(RequestPermissionOutcome.Cancelled, pending.await())
            assertFalse(otherSession.isCompleted)
            assertTrue(
                bridge.events.replayCache.any {
                    it is AppSessionEvent.ToolPermissionResolved && it.toolCallId == "tc1"
                },
            )
        }
}
