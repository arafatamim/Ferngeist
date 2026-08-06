package com.tamimarafat.ferngeist.acp.bridge.connection

import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

class SessionGatewayTest {

    private class ConnectivityStub : ConnectivityObserver {
        private val _isConnected = MutableStateFlow(true)
        override val isConnected: Flow<Boolean> = _isConnected
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
}
