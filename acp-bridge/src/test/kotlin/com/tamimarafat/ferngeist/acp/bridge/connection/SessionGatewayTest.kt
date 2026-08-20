package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
