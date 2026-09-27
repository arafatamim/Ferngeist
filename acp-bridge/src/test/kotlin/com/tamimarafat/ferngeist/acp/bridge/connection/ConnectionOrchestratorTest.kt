package com.tamimarafat.ferngeist.acp.bridge.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listing seam's failure contract.
 *
 * `listSessions` used to answer every failure with `emptyList()`, which no caller can tell
 * apart from "the agent has no sessions" — and `ChatConnectionHub` persists what it gets, so
 * a failed listing deleted the user's cached sessions (via `SessionDao.replaceSessions`), showed
 * no error, and left the flag that drives the reconnect retry cleared.
 */
class ConnectionOrchestratorTest {
    private class ConnectivityStub : ConnectivityObserver {
        private val _isConnected = MutableStateFlow(true)
        override val isConnected: Flow<Boolean> = _isConnected
    }

    private fun newOrchestrator(): ConnectionOrchestrator =
        ConnectionOrchestrator(
            connectivityObserver = ConnectivityStub(),
            gatewayRepository = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun `listing without a connected client fails instead of reporting no sessions`() =
        runTest {
            val result = runCatching { newOrchestrator().listSessions(cwd = null) }

            assertTrue(
                "a listing that could not run must not read as an empty answer, got $result",
                result.exceptionOrNull() is SessionListingUnavailableException,
            )
        }
}
