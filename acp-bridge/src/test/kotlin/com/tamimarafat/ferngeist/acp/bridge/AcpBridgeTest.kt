package com.tamimarafat.ferngeist.acp.bridge

import app.cash.turbine.test
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionListCapabilities
import com.agentclientprotocol.model.SessionResumeCapabilities
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.protocol.JsonRpcException
import com.tamimarafat.ferngeist.acp.bridge.connection.PermissionFlow
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpDiagnosticsStore
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.connection.displayLabels
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMode
import com.tamimarafat.ferngeist.acp.bridge.session.allChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBridgeTest {
    @Test
    fun `SessionBridge should initialize with correct sessionId`() =
        runTest {
            val sessionId = "test_session_123"
            val bridge = SessionBridge(sessionId, null)

            assertEquals(sessionId, bridge.sessionId)
        }

    @Test
    fun `SessionBridge should surface legacy modes as config options`() =
        runTest {
            val bridge = SessionBridge("test_session", null)

            bridge.emitEvent(
                AppSessionEvent.ModesUpdated(
                    modes =
                        listOf(
                            SessionMode(id = "code", name = "Code"),
                            SessionMode(id = "ask", name = "Ask"),
                        ),
                    currentModeId = "code",
                ),
            )

            val option =
                bridge.snapshot.value.configOptions
                    .first() as SessionConfigOption.Select
            assertTrue(option.category is SessionConfigCategory.Mode)
            assertEquals("code", option.currentValue)
            assertEquals(2, option.allChoices().size)
        }

    @Test
    fun `SessionBridge should expose config options in snapshot`() =
        runTest {
            val bridge = SessionBridge("test_session", null)
            assertTrue(
                bridge.snapshot.value.configOptions
                    .isEmpty(),
            )
        }

    @Test
    fun `SessionBridge should emit events via emitEvent`() =
        runTest {
            val bridge = SessionBridge("test_session", null)

            val collectJob =
                launch {
                    bridge.events.test {
                        val event = awaitItem()
                        assertEquals("hello", (event as AppSessionEvent.AgentMessage).text)
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            bridge.emitEvent(AppSessionEvent.AgentMessage("hello"))
            collectJob.join()
        }

    @Test
    fun `SessionBridge sendPrompt should execute without throwing`() =
        runTest {
            val bridge = SessionBridge("test_session", null)
            bridge.sendPrompt("Hello world")
            assertTrue(true)
        }
}

class ConnectivityObserverStub(
    initialState: Boolean = true,
) : ConnectivityObserver {
    private val _isConnected = MutableStateFlow(initialState)
    override val isConnected: Flow<Boolean> = _isConnected

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }
}

class AcpConnectionManagerTest {
    @Test
    fun `AcpConnectionManager should start in disconnected state`() =
        runTest {
            val connectivityObserver = ConnectivityObserverStub(initialState = true)
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val manager = AcpConnectionManager(connectivityObserver, scope)

            assertEquals(AcpConnectionState.Disconnected, manager.connectionState.value)
        }

    @Test
    fun `AcpConnectionManager getSession should return null for unknown session`() =
        runTest {
            val connectivityObserver = ConnectivityObserverStub(initialState = true)
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val manager = AcpConnectionManager(connectivityObserver, scope)

            val session = manager.getSession("unknown")

            assertEquals(null, session)
        }

    @Test
    fun `AcpConnectionManager should track isConnected state`() =
        runTest {
            val connectivityObserver = ConnectivityObserverStub(initialState = false)
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val manager = AcpConnectionManager(connectivityObserver, scope)

            assertFalse(manager.isConnected)
        }

    @Test
    fun `connect failure does not emit synthetic disconnected event`() =
        runTest {
            val manager =
                AcpConnectionManager(
                    connectivityObserver = ConnectivityObserverStub(initialState = true),
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            manager.events.test {
                val connected =
                    manager.connect(
                        AcpConnectionConfig(
                            host = "127.0.0.1:1",
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
    fun `PermissionFlow cancelForSession cancels only matching session pendings`() =
        runTest {
            val flow = PermissionFlow()
            val deferred1 = CompletableDeferred<RequestPermissionOutcome>()
            val deferred2 = CompletableDeferred<RequestPermissionOutcome>()

            flow.addPending("tool-1", "session-1", deferred1)
            flow.addPending("tool-2", "session-2", deferred2)

            flow.cancelForSession("session-1")

            assertTrue(deferred1.isCancelled)
            assertNull(flow.takePending("tool-1"))
            assertNotNull(flow.takePending("tool-2"))
        }

    @Test
    fun `PermissionFlow cancelAll cleans up all pending requests`() =
        runTest {
            val flow = PermissionFlow()
            val deferred = CompletableDeferred<RequestPermissionOutcome>()
            flow.addPending("tool-a", "session-x", deferred)

            flow.cancelAll()

            assertTrue(deferred.isCancelled)
            assertNull(flow.takePending("tool-a"))
        }

    @Test
    fun `diagnostics starts with supportsSessionCancel null`() =
        runTest {
            val connectivityObserver = ConnectivityObserverStub(initialState = true)
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val manager = AcpConnectionManager(connectivityObserver, scope)

            assertNull(manager.diagnostics.value.supportsSessionCancel)
        }

    @Test
    fun `setSessionCancelSupport false updates diagnostics flag`() =
        runTest {
            val store = AcpDiagnosticsStore()
            store.setSessionCancelSupport(false)
            assertFalse(store.diagnostics.value.supportsSessionCancel!!)
        }

    @Test
    fun `setSessionCancelSupport true updates diagnostics flag`() =
        runTest {
            val store = AcpDiagnosticsStore()
            store.setSessionCancelSupport(true)
            assertTrue(store.diagnostics.value.supportsSessionCancel!!)
        }

    @Test
    fun `awaitConnectivityForReconnect waits until observer reports online`() =
        runTest {
            val connectivityObserver = ConnectivityObserverStub(initialState = false)
            val manager =
                AcpConnectionManager(
                    connectivityObserver = connectivityObserver,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val waitJob =
                launch {
                    manager.awaitConnectivityForReconnect()
                }

            assertFalse(waitJob.isCompleted)
            connectivityObserver.setConnected(true)
            waitJob.join()
            assertTrue(waitJob.isCompleted)
        }
}

class AcpConnectionConfigTest {
    @Test
    fun `should carry preferred auth method id`() {
        val config =
            AcpConnectionConfig(
                host = "localhost:8080",
                preferredAuthMethodId = "env:github_token",
            )

        assertEquals("env:github_token", config.preferredAuthMethodId)
    }

    @Test
    fun `should default preferred auth method id to null`() {
        val config =
            AcpConnectionConfig(
                host = "localhost:8080",
            )

        assertNull(config.preferredAuthMethodId)
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
    fun `formats cancellation errors using fallback instead of coroutine internals`() {
        val error = CancellationException("StandaloneCoroutine was cancelled")

        val formatted = formatAcpErrorMessage(error, "Connection lost")

        assertEquals("Connection lost", formatted)
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
                sessionCapabilities = SessionCapabilities(list = SessionListCapabilities(), resume = SessionResumeCapabilities()),
            )

        assertEquals(
            listOf("Load", "Images", "Context", "MCP HTTP", "List", "Resume"),
            capabilities.displayLabels(),
        )
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `display labels are empty when nothing is advertised`() {
        assertTrue(AgentCapabilities().displayLabels().isEmpty())
    }
}
