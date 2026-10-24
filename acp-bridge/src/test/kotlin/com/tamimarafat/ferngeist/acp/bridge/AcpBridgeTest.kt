package com.tamimarafat.ferngeist.acp.bridge

import app.cash.turbine.test
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionListCapabilities
import com.agentclientprotocol.model.SessionResumeCapabilities
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionListingUnavailableException
import com.tamimarafat.ferngeist.acp.bridge.connection.displayLabels
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.isCancellationLikeError
import com.tamimarafat.ferngeist.acp.bridge.connection.sessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.facade.isSessionCancelUnsupported
import com.tamimarafat.ferngeist.acp.bridge.facade.mapCapabilities
import com.tamimarafat.ferngeist.acp.bridge.facade.userFacingSendError
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Manager wiring for tests that never reach the gateway resume path. */
private val noGatewayResume: suspend (
    gateway: AcpConnectionConfig.Gateway,
    session: AcpConnectionConfig.ResumeInfo,
) -> String = { _, _ -> error("no gateway resume in this test") }

class SessionBridgeTest {
    @Test
    fun `SessionBridge should deliver model selection confirmations on the narrow flow`() =
        runTest {
            val bridge = SessionBridge("test_session", null)

            bridge.modelSelectionEvents.test {
                bridge.emitEvent(AppSessionEvent.ModelSelectionConfirmed("gpt-5"))
                assertEquals("gpt-5", awaitItem().modelId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `SessionBridge should not deliver other events on modelSelectionEvents`() =
        runTest {
            val bridge = SessionBridge("test_session", null)

            bridge.modelSelectionEvents.test {
                bridge.emitEvent(AppSessionEvent.AgentMessage("hello"))
                bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `SessionBridge should not replay the whole session history to a late collector`() =
        runTest {
            val bridge = SessionBridge("test_session", null)
            repeat(50) { index -> bridge.emitEvent(AppSessionEvent.AgentMessage("chunk $index")) }

            val replayed =
                bridge.events.replayCache.map { (it as AppSessionEvent.AgentMessage).text }

            // The fix: a live session must not keep every event it has ever emitted.
            assertFalse("the whole event history must not be retained", replayed.contains("chunk 0"))
            assertEquals("chunk 49", replayed.last())
        }
}

class ConnectivityObserverStub(
    initialState: Boolean = true,
) : ConnectivityObserver {
    private val _isConnected = MutableStateFlow(initialState)
    override val isConnected: Flow<Boolean> = _isConnected
}

@OptIn(ExperimentalCoroutinesApi::class)
class AcpConnectionTest {
    @Test
    fun `AcpConnection should start in disconnected state`() =
        runTest {
            val connectivityObserver = ConnectivityObserverStub(initialState = true)
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            val manager = AcpConnection(connectivityObserver, noGatewayResume, scope)

            assertEquals(AcpConnectionState.Disconnected, manager.connectionState.value)
            assertFalse(manager.isConnected)
        }

    @Test
    fun `connect failure does not emit synthetic disconnected event`() =
        runTest {
            val manager =
                AcpConnection(
                    connectivityObserver = ConnectivityObserverStub(initialState = true),
                    resumeAttachToken = noGatewayResume,
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            manager.events.test {
                val connected =
                    manager.connect(
                        AcpConnectionConfig.Manual(
                            scheme = "ws",
                            host = "127.0.0.1:1",
                            preferredAuthMethodId = null,
                            displayName = null,
                        ),
                    )

                assertFalse(connected)
                assertTrue(manager.connectionState.value is AcpConnectionState.Failed)
                expectNoEvents()

                manager.disconnect()
                assertEquals(AcpManagerEvent.Disconnected, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `diagnostics starts with supportsSessionCancel null and records both setter values`() =
        runTest {
            val connectivityObserver = ConnectivityObserverStub(initialState = true)
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            val manager = AcpConnection(connectivityObserver, noGatewayResume, scope)

            assertNull(manager.diagnostics.value.supportsSessionCancel)

            manager.diagnosticsStore.setSessionCancelSupport(false)
            assertFalse(manager.diagnostics.value.supportsSessionCancel!!)

            manager.diagnosticsStore.setSessionCancelSupport(true)
            assertTrue(manager.diagnostics.value.supportsSessionCancel!!)
        }

    /**
     * The listing seam's failure contract: `listSessions` used to answer every failure
     * with `emptyList()`, which no caller can tell apart from "the agent has no sessions"
     * — and `ChatConnectionHub` persists what it gets, so a failed listing deleted the
     * user's cached sessions, showed no error, and left the flag that drives the
     * reconnect retry cleared.
     */
    @Test
    fun `listing without a connected client fails instead of reporting no sessions`() =
        runTest {
            val connection =
                AcpConnection(
                    connectivityObserver = ConnectivityObserverStub(initialState = true),
                    resumeAttachToken = noGatewayResume,
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            val result = runCatching { connection.listSessions(cwd = null) }

            assertTrue(
                "a listing that could not run must not read as an empty answer, got $result",
                result.exceptionOrNull() is SessionListingUnavailableException,
            )
        }
}

class AcpErrorFormattingTest {
    @Test
    fun `formats json rpc string data into user facing message`() {
        val error =
            JsonRpcException(
                code = -32603,
                message = "Internal error",
                data = kotlinx.serialization.json.JsonPrimitive("CODEX_API_KEY is not set"),
            )

        val formatted = formatAcpErrorMessage(error, "Request failed")

        assertEquals("Internal error: CODEX_API_KEY is not set", formatted)
    }

    @Test
    fun `formats json rpc object data by stringifying it`() {
        val error =
            JsonRpcException(
                code = -32603,
                message = "Internal error",
                data =
                    buildJsonObject {
                        put("missing", "CODEX_API_KEY")
                        put("kind", "env")
                    },
            )

        val formatted = formatAcpErrorMessage(error, "Request failed")

        assertTrue(formatted.startsWith("Internal error: "))
        assertTrue(formatted.contains("\"missing\":\"CODEX_API_KEY\""))
    }

    @Test
    fun `truncates oversized json rpc data so error payloads do not flood the ui`() {
        val hugeBase64 = "A".repeat(5000)
        val error =
            JsonRpcException(
                code = -32602,
                message = "Invalid params",
                data = kotlinx.serialization.json.JsonPrimitive(hugeBase64),
            )

        val formatted = formatAcpErrorMessage(error, "Request failed")

        assertTrue(formatted.startsWith("Invalid params: "))
        assertTrue(formatted.endsWith("… (truncated)"))
        assertTrue("error text must stay bounded", formatted.length < 300)
    }

    @Test
    fun `formats cancellation errors using fallback instead of coroutine internals`() {
        val error = CancellationException("StandaloneCoroutine was cancelled")

        val formatted = formatAcpErrorMessage(error, "Connection lost")

        assertEquals("Connection lost", formatted)
    }

    @Test
    fun `a failure that only mentions cancellation is not treated as one`() {
        // The old wording test also matched *failures*: a gateway error body, a peer's
        // "handshake was cancelled by the peer". Callers route on this predicate, and a
        // misread marked the connection Disconnected — no reconnect, no diagnostics entry —
        // so a real failure produced a dead chat with no cause on record.
        val error = IOException("handshake was cancelled by the peer")

        assertFalse(isCancellationLikeError(error))
        assertEquals("handshake was cancelled by the peer", formatAcpErrorMessage(error, "Connection lost"))
    }

    @Test
    fun `a timeout is a failure rather than a cancellation`() =
        runTest {
            // TimeoutCancellationException extends CancellationException by inheritance but
            // means "we ran out of time", which must still surface as a failure and a reconnect.
            // Its constructor is internal, so the timeout is produced the way production does.
            val timeout = runCatching { withTimeout(1) { delay(50) } }.exceptionOrNull()

            assertTrue("expected a timeout, got $timeout", timeout is TimeoutCancellationException)
            assertFalse(isCancellationLikeError(timeout!!))
        }

    @Test
    fun `a real cancellation is still recognised through a wrapper`() {
        assertTrue(isCancellationLikeError(CancellationException("StandaloneCoroutine was cancelled")))
        assertTrue(isCancellationLikeError(IllegalStateException("wrapped", CancellationException("gone"))))
    }
}

/**
 * Classification of send/cancel failures. Both predicates used to read the peer's prose;
 * one replaced the agent's own message, the other needed wording the protocol does not
 * require, so both now read the JSON-RPC code.
 */
class AcpSendErrorClassificationTest {
    @Test
    fun `a generic error keeps the agent's own detail`() {
        val error =
            JsonRpcException(
                code = JsonRpcErrorCode.INTERNAL_ERROR.code,
                message = "Invalid params: cwd does not exist",
                data = JsonNull,
            )

        assertTrue(
            "the agent's detail is the only actionable part: ${userFacingSendError(error)}",
            userFacingSendError(error).contains("cwd does not exist"),
        )
    }

    @Test
    fun `an invalid-params code is classified without reading the message`() {
        val error =
            JsonRpcException(
                code = JsonRpcErrorCode.INVALID_PARAMS.code,
                message = "Something else entirely",
                data = JsonNull,
            )

        assertEquals("Send failed due to an invalid request format.", userFacingSendError(error))
    }

    @Test
    fun `the code alone says the agent cannot cancel`() {
        // A legal bare -32601 carries no method name. Requiring one left the flag unset and
        // the user with a Cancel button that could never work.
        val error =
            JsonRpcException(
                code = JsonRpcErrorCode.METHOD_NOT_FOUND.code,
                message = "Method not found",
                data = JsonNull,
            )

        assertTrue(isSessionCancelUnsupported(error))
    }

    @Test
    fun `wording alone does not say the agent cannot cancel`() {
        val error =
            JsonRpcException(
                code = JsonRpcErrorCode.INTERNAL_ERROR.code,
                message = "Method not found: session/cancel",
                data = JsonNull,
            )

        assertFalse(isSessionCancelUnsupported(error))
    }
}

class AcpAgentCapabilitiesTest {
    @OptIn(UnstableApi::class)
    @Test
    fun `display labels include advertised capabilities`() {
        val capabilities =
            AgentCapabilities(
                loadSession = true,
                promptCapabilities = PromptCapabilities(image = true, embeddedContext = true),
                mcpCapabilities = McpCapabilities(http = true),
                sessionCapabilities =
                    SessionCapabilities(
                        list = SessionListCapabilities(),
                        resume = SessionResumeCapabilities(),
                    ),
            )

        assertEquals(
            listOf("Load", "Images", "Context", "MCP HTTP", "List", "Resume"),
            capabilities.displayLabels(),
        )
        assertTrue(
            "an agent that advertises nothing must show no labels",
            AgentCapabilities().displayLabels().isEmpty(),
        )
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `attach RPC is load when the agent advertises load`() {
        assertEquals(
            SessionAttachRpc.LOAD,
            AgentCapabilities(loadSession = true).sessionAttachRpc(),
        )
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `attach RPC is load while the capabilities are still unobserved`() {
        val capabilities: AgentCapabilities? = null

        assertEquals(SessionAttachRpc.LOAD, capabilities.sessionAttachRpc())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `attach RPC prefers load over resume when both are advertised`() {
        val capabilities =
            AgentCapabilities(
                loadSession = true,
                sessionCapabilities = SessionCapabilities(resume = SessionResumeCapabilities()),
            )

        assertEquals(SessionAttachRpc.LOAD, capabilities.sessionAttachRpc())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `attach RPC is resume for a resume-only agent`() {
        val capabilities =
            AgentCapabilities(
                loadSession = false,
                sessionCapabilities = SessionCapabilities(resume = SessionResumeCapabilities()),
            )

        assertEquals(SessionAttachRpc.RESUME, capabilities.sessionAttachRpc())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `attach RPC is null when the agent advertises neither load nor resume`() {
        val capabilities = AgentCapabilities(loadSession = false)

        assertNull(capabilities.sessionAttachRpc())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `capabilities map to chat capabilities in both directions`() {
        val resumeOnly =
            AgentCapabilities(
                loadSession = false,
                sessionCapabilities = SessionCapabilities(resume = SessionResumeCapabilities()),
            )

        assertFalse(mapCapabilities(resumeOnly).supportsHistoryReplay)
        assertTrue(mapCapabilities(AgentCapabilities(loadSession = true)).supportsHistoryReplay)
    }
}
