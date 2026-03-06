package com.tamimarafat.ferngeist.acp.bridge

import app.cash.turbine.test
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.agentclientprotocol.model.RequestPermissionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBridgeTest {

    @Test
    fun `SessionBridge should initialize with correct sessionId`() = runTest {
        val sessionId = "test_session_123"
        val bridge = SessionBridge(sessionId, null, CoroutineScope(Dispatchers.Unconfined))

        assertEquals(sessionId, bridge.sessionId)
    }

    @Test
    fun `SessionBridge should emit mode changes`() = runTest {
        val bridge = SessionBridge("test_session", null, CoroutineScope(Dispatchers.Unconfined))

        val collectJob = launch {
            bridge.currentModeId.test {
                assertEquals("code", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
        bridge.setMode("code")
        collectJob.join()
    }

    @Test
    fun `SessionBridge should expose config options flow`() = runTest {
        val bridge = SessionBridge("test_session", null, CoroutineScope(Dispatchers.Unconfined))

        val collectJob = launch {
            bridge.configOptions.test {
                assertTrue(awaitItem().isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }
        collectJob.join()
    }

    @Test
    fun `SessionBridge should emit events via emitEvent`() = runTest {
        val bridge = SessionBridge("test_session", null, CoroutineScope(Dispatchers.Unconfined))

        val collectJob = launch {
            bridge.events.test {
                val event = awaitItem()
                assertEquals("hello", (event as com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent.AgentMessage).text)
                cancelAndIgnoreRemainingEvents()
            }
        }
        bridge.emitEvent(com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent.AgentMessage("hello"))
        collectJob.join()
    }

    @Test
    fun `SessionBridge sendPrompt should execute without throwing`() = runTest {
        val bridge = SessionBridge("test_session", null, CoroutineScope(Dispatchers.Unconfined))
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
    fun `AcpConnectionManager should start in disconnected state`() = runTest {
        val connectivityObserver = ConnectivityObserverStub(initialState = true)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = AcpConnectionManager(connectivityObserver, scope)

        assertEquals(AcpConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `AcpConnectionManager getSession should return null for unknown session`() = runTest {
        val connectivityObserver = ConnectivityObserverStub(initialState = true)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = AcpConnectionManager(connectivityObserver, scope)

        val session = manager.getSession("unknown")

        assertEquals(null, session)
    }

    @Test
    fun `AcpConnectionManager should track isConnected state`() = runTest {
        val connectivityObserver = ConnectivityObserverStub(initialState = false)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = AcpConnectionManager(connectivityObserver, scope)

        assertFalse(manager.isConnected)
    }

    @Test
    fun `connect failure does not emit synthetic disconnected event`() = runTest {
        val manager = AcpConnectionManager(
            connectivityObserver = ConnectivityObserverStub(initialState = true),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        manager.events.test {
            val connected = manager.connect(
                AcpConnectionConfig(
                    host = "127.0.0.1:1",
                    authToken = "",
                )
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
    fun `removeSession clears pending permission requests for that session`() = runTest {
        val manager = AcpConnectionManager(
            connectivityObserver = ConnectivityObserverStub(initialState = true),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val deferred = CompletableDeferred<RequestPermissionOutcome>()

        manager.privateRegistryMap<SessionBridge>("sessionBridges")["session-1"] =
            SessionBridge("session-1", null, CoroutineScope(Dispatchers.Unconfined))
        manager.privateRegistryMap<Any>("pendingPermissionRequests")["tool-1"] =
            createPendingPermissionRequest(sessionId = "session-1", deferred = deferred)
        manager.privateRegistryMap<Any>("pendingPermissionRequests")["tool-2"] =
            createPendingPermissionRequest(
                sessionId = "session-2",
                deferred = CompletableDeferred<RequestPermissionOutcome>(),
            )

        manager.removeSession("session-1")

        assertNull(manager.getSession("session-1"))
        assertTrue(deferred.isCancelled)
        assertFalse(manager.privateRegistryMap<Any>("pendingPermissionRequests").containsKey("tool-1"))
        assertTrue(manager.privateRegistryMap<Any>("pendingPermissionRequests").containsKey("tool-2"))
    }

    @Test
    fun `unsupported session cancel failure downgrades diagnostics`() = runTest {
        val manager = AcpConnectionManager(
            connectivityObserver = ConnectivityObserverStub(initialState = true),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        manager.invokePrivate(
            methodName = "handleSessionCancelFailure",
            parameterTypes = arrayOf(Throwable::class.java),
            args = arrayOf(IllegalStateException("Method not found: session/cancel")),
        )

        assertEquals(false, manager.diagnostics.value.supportsSessionCancel)
        assertTrue(
            manager.diagnostics.value.recentErrors.any { error ->
                error.source == "session/cancel"
            }
        )
    }

    @Test
    fun `awaitConnectivityForReconnect waits until observer reports online`() = runTest {
        val connectivityObserver = ConnectivityObserverStub(initialState = false)
        val manager = AcpConnectionManager(
            connectivityObserver = connectivityObserver,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val waitJob = launch {
            manager.awaitConnectivityForReconnect()
        }

        assertFalse(waitJob.isCompleted)
        connectivityObserver.setConnected(true)
        waitJob.join()
        assertTrue(waitJob.isCompleted)
    }
}

private fun createPendingPermissionRequest(
    sessionId: String,
    deferred: CompletableDeferred<RequestPermissionOutcome>,
): Any {
    val clazz = Class.forName("com.tamimarafat.ferngeist.acp.bridge.connection.PendingPermissionRequest")
    val constructor = clazz.getDeclaredConstructor(String::class.java, CompletableDeferred::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(sessionId, deferred)
}

@Suppress("UNCHECKED_CAST")
private fun <T> AcpConnectionManager.privateRegistryMap(fieldName: String): MutableMap<String, T> {
    val registry = readPrivateField<Any>("sessionRegistry")
        ?: error("sessionRegistry not found")
    val field = registry.javaClass.getDeclaredField(fieldName)
    field.isAccessible = true
    return field.get(registry) as MutableMap<String, T>
}

@Suppress("UNCHECKED_CAST")
private fun <T> Any.readPrivateField(fieldName: String): T? {
    val field = javaClass.getDeclaredField(fieldName)
    field.isAccessible = true
    return field.get(this) as T?
}

private fun AcpConnectionManager.invokePrivate(
    methodName: String,
    parameterTypes: Array<Class<*>>,
    args: Array<Any>,
): Any? {
    val method = AcpConnectionManager::class.java.getDeclaredMethod(methodName, *parameterTypes)
    method.isAccessible = true
    return method.invoke(this, *args)
}

class AuthHeaderBuilderTest {

    @Test
    fun `should build auth header with token`() {
        val config = AcpConnectionConfig(
            host = "localhost:8080",
            authToken = "test_token",
        )

        val headers = com.tamimarafat.ferngeist.acp.bridge.connection.AuthHeaderBuilder.build(config)

        assertEquals("Bearer test_token", headers["Authorization"])
    }

    @Test
    fun `should not include empty headers`() {
        val config = AcpConnectionConfig(
            host = "localhost:8080",
            authToken = "",
        )

        val headers = com.tamimarafat.ferngeist.acp.bridge.connection.AuthHeaderBuilder.build(config)

        assertFalse(headers.containsKey("Authorization"))
    }
}
