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
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.transport.Transport
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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

@OptIn(UnstableApi::class)
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
    ) : ClientSession {
        private var turnIndex = 0

        /** Completed as soon as [cancel] is entered, so a test can order against the RPC. */
        val cancelStarted = CompletableDeferred<Unit>()

        override val sessionId: SessionId get() = error("unused")
        override val parameters: SessionCreationParameters get() = error("unused")
        override val client: Client get() = error("unused")
        override val operations: ClientSessionOperations get() = error("unused")

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
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val orchestra =
            ConnectionOrchestrator(
                connectivityObserver = ConnectivityStub(),
                gatewayRepository = null,
                scope = scope,
            )
        return SessionGateway(
            orchestra = orchestra,
            permissionFlow = PermissionFlow(),
            bridgeFactory = { sessionId -> SessionBridge(sessionId, null) },
            scope = scope,
        )
    }

    /**
     * The gateway builds its [AcpSessionRegistry] internally and exposes no injection
     * seam, so tests reach the private field reflectively — the same approach
     * [com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHubTest] uses for `orchestra`.
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
     * `loadSession`'s real `session/load` path: an SDK session in the registry makes it
     * treat the session as already loaded and return early.
     */
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
     * Installs an SDK client on the orchestrator's transport. `AcpTransportClient.sdkClient`
     * is `private set` and only assigned by a real connect, so a test that needs
     * `loadSession` past its disconnected check has to set it reflectively.
     */
    private fun installSdkClient(
        gateway: SessionGateway,
        client: Client,
    ) {
        val orchestra = gatewayField(gateway, "orchestra")
        val transportField = orchestra.javaClass.getDeclaredField("transportClient")
        transportField.isAccessible = true
        val transport = transportField.get(orchestra)!!
        val clientField = transport.javaClass.getDeclaredField("sdkClient")
        clientField.isAccessible = true
        clientField.set(transport, client)
    }

    /**
     * A [Client] whose next outbound `session/load` throws [error].
     *
     * The SDK's [Client] is a final class — it can be neither proxied nor subclassed — and
     * this module has no mocking library. Its collaborator [Transport] is an interface
     * though, so the failure is injected at the wire instead: a real client over a real
     * protocol whose transport refuses every message. Only requests routed through it fail.
     */
    private fun clientFailingSessionLoad(error: Throwable): Client =
        Client(
            Protocol(
                parentScope = CoroutineScope(Dispatchers.Unconfined),
                transport = FailingSendTransport(error),
                options = ProtocolOptions(protocolDebugName = "SessionGatewayTest"),
            ),
        )

    /** A [Transport] whose every outbound message fails with [error]. */
    private class FailingSendTransport(
        private val error: Throwable,
    ) : Transport {
        override val state: StateFlow<Transport.State> = MutableStateFlow(Transport.State.STARTED)

        override fun start() = Unit

        override fun send(message: JsonRpcMessage): Unit = throw error

        override fun onMessage(handler: (JsonRpcMessage) -> Unit) = Unit

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
    fun `JsonRpcException with INVALID_PARAMS code and non-matching message is already loaded`() =
        runTest {
            val gateway = newGateway()
            val error =
                JsonRpcException(
                    code = JsonRpcErrorCode.INVALID_PARAMS.code,
                    message = "Some unrelated server error text",
                    data = JsonNull,
                )
            assertTrue(gateway.isSessionAlreadyLoadedError(error))
        }

    @Test
    fun `message substring fallback matches without INVALID_PARAMS code`() =
        runTest {
            val gateway = newGateway()
            val error =
                JsonRpcException(
                    code = JsonRpcErrorCode.INTERNAL_ERROR.code,
                    message = "Session is already loaded on another client",
                    data = JsonNull,
                )
            assertTrue(gateway.isSessionAlreadyLoadedError(error))
        }

    @Test
    fun `plain exception with already loaded message matches`() =
        runTest {
            val gateway = newGateway()
            val error = IllegalStateException("Session already loaded")
            assertTrue(gateway.isSessionAlreadyLoadedError(error))
        }

    @Test
    fun `INVALID_PARAMS code deep in cause chain matches`() =
        runTest {
            val gateway = newGateway()
            val rpcError =
                JsonRpcException(
                    code = JsonRpcErrorCode.INVALID_PARAMS.code,
                    message = "Bad request",
                    data = JsonNull,
                )
            val error = IllegalStateException("outer failure", rpcError)
            assertTrue(gateway.isSessionAlreadyLoadedError(error))
        }

    @Test
    fun `unrelated error is not already loaded`() =
        runTest {
            val gateway = newGateway()
            val error = IllegalStateException("Something else failed")
            assertFalse(gateway.isSessionAlreadyLoadedError(error))
        }

    @Test
    fun `createSession with blank cwd returns null`() =
        runTest {
            val gateway = newGateway()
            assertNull(gateway.createSession(""))
        }

    @Test
    fun `createSession with whitespace cwd returns null`() =
        runTest {
            val gateway = newGateway()
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
    fun `loadSession rethrows cancellation instead of clearing the session`() =
        runTest {
            val gateway = newGateway()
            val bridge = installBridge(gateway, "s1")
            installSdkClient(gateway, clientFailingSessionLoad(CancellationException("screen closed")))

            val result = runCatching { gateway.loadSession("s1", "/some/cwd") }

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertSame("cancellation must not tear the session down", bridge, gateway.getSession("s1"))
            assertEquals(SessionLoadState.HYDRATING, bridge.snapshot.value.loadState)
        }

    @Test
    fun `loadSession still clears the session on a real load failure`() =
        runTest {
            val gateway = newGateway()
            installBridge(gateway, "s1")
            installSdkClient(gateway, clientFailingSessionLoad(IllegalStateException("transport boom")))

            val result = runCatching { gateway.loadSession("s1", "/some/cwd") }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertNull("a real load failure must clear the session", gateway.getSession("s1"))
        }

    @Test
    fun `loadSession clears the session when the load deadline expires`() =
        runTest {
            val gateway = newGateway()
            val bridge = installBridge(gateway, "s1")
            // The exception has an internal constructor, so produce a real one the way the
            // production path does: the deadline on the caller's `withTimeout` fires.
            val timedOut = runCatching { withTimeout(1) { delay(1_000) } }.exceptionOrNull()!!
            installSdkClient(gateway, clientFailingSessionLoad(timedOut))

            val result = runCatching { gateway.loadSession("s1", "/some/cwd") }

            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            assertNull(
                "a timed-out load must not leave the session registered and HYDRATING",
                gateway.getSession("s1"),
            )
            assertEquals(SessionLoadState.FAILED, bridge.snapshot.value.loadState)
        }

    @Test
    fun `prompt stream ending without a response emits end_turn`() =
        runTest {
            val gateway = newGateway()
            val bridge = installSession(gateway, "s1", FakeClientSession(listOf(emptyFlow())))

            gateway.sendSessionMessage("s1", "hello")

            assertEquals(listOf("end_turn"), turnReasons(bridge))
        }

    @Test
    fun `a cancel while the turn is in flight reports cancelled and does not leak to the next turn`() =
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
                            emptyFlow(),
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

            assertEquals(listOf("cancelled", "end_turn"), turnReasons(bridge))
        }

    @Test
    fun `a turn that receives a response clears the cancel mark`() =
        runTest {
            val gateway = newGateway()
            val bridge =
                installSession(
                    gateway,
                    "s1",
                    FakeClientSession(listOf(responseTurn(), emptyFlow())),
                )

            gateway.cancelSession("s1")
            gateway.sendSessionMessage("s1", "first")
            gateway.sendSessionMessage("s1", "second")

            assertEquals(listOf("end_turn", "end_turn"), turnReasons(bridge))
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
            assertEquals(listOf("end_turn"), turnReasons(bridge))
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
                    throw IllegalStateException("aborted")
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
                    FakeClientSession(listOf(responseTurn(), emptyFlow())),
                )

            // Turn 1 ends normally, with no response expected after it.
            gateway.sendSessionMessage("s1", "first")
            // A cancel lands after the turn is over (user tapped STOP late).
            gateway.cancelSession("s1")
            // Turn 2 must not inherit that stale mark.
            gateway.sendSessionMessage("s1", "second")

            assertEquals(listOf("end_turn", "end_turn"), turnReasons(bridge))
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
                            },
                            emptyFlow(),
                        ),
                    // The RPC does not return until the test releases it, so the turn
                    // stream ends while the cancel is still in flight.
                    cancelGate = CompletableDeferred(),
                )
            val bridge = installSession(gateway, "s1", session)

            val first = launch { gateway.sendSessionMessage("s1", "first") }
            collecting.await()
            val cancelling = launch { gateway.cancelSession("s1") }
            session.cancelStarted.await()
            turn.close() // the turn ends before session.cancel() returns
            first.join()
            session.cancelGate?.complete(Unit) // now let the RPC return
            cancelling.join()

            gateway.sendSessionMessage("s1", "second")

            // Marking before the RPC is what makes this the cancelled turn; marking
            // after it would report end_turn here and strand "cancelled" on turn two.
            assertEquals(listOf("cancelled", "end_turn"), turnReasons(bridge))
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
