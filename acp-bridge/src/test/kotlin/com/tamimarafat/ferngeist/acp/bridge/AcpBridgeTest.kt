package com.tamimarafat.ferngeist.acp.bridge

import app.cash.turbine.test
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
