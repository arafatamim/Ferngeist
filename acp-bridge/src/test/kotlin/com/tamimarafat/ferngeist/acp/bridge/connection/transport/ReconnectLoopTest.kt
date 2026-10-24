package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpDiagnosticsStore
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.connection.ReconnectLoop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectLoopTest {
    @Test
    fun `an offline reconnect stays pending until connectivity returns and it succeeds`() =
        runTest {
            val online = MutableStateFlow(false)
            val observer =
                object : ConnectivityObserver {
                    override val isConnected = online
                }
            val loop =
                ReconnectLoop(
                    scope = this,
                    connectivityObserver = observer,
                    diagnosticsStore = AcpDiagnosticsStore(),
                    isLive = { false },
                    currentConfig = { AcpConnectionConfig.Manual("ws", "agent:5788", null, null) },
                    attemptOnce = { _, _ -> true },
                )

            loop.schedule {}
            advanceUntilIdle()
            assertTrue(loop.pending.value)

            online.value = true
            advanceUntilIdle()
            assertFalse(loop.pending.value)
        }
}
