package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpDiagnosticsStore
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.connection.ReconnectLoop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectLoopTest {
    @Test
    fun `an offline open waits for connectivity, then runs once and stops`() =
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
                    currentConfig = { null },
                    attemptOnce = { _, _ -> error("no transport in this test") },
                )
            var attempts = 0

            loop.retry {
                attempts++
                true
            }
            advanceUntilIdle()
            assertTrue(loop.pending.value)
            assertEquals("nothing is tried while offline", 0, attempts)

            online.value = true
            advanceUntilIdle()
            assertFalse(loop.pending.value)
            assertEquals(1, attempts)
        }

    @Test
    fun `an offline open takes over a loop the transport armed, so only the open runs`() =
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
                    currentConfig = { null },
                    attemptOnce = { _, _ -> error("no transport in this test") },
                )
            val ran = mutableListOf<String>()

            loop.retry {
                ran += "socket"
                false
            }
            loop.takeOver {
                ran += "open"
                true
            }
            online.value = true
            advanceUntilIdle()

            assertEquals(listOf("open"), ran)
            assertFalse(loop.pending.value)
        }
}
