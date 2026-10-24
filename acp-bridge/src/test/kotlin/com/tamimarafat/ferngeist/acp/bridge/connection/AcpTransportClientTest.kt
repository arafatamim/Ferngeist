package com.tamimarafat.ferngeist.acp.bridge.connection

import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AcpTransportClientTest {
    @Test
    fun `manual config derives its endpoint from scheme and host`() {
        assertEquals("ws://192.168.1.5:5788", manualConfig().endpoint)
    }

    // ---- Reconnect backoff ----

    @Test
    fun `backoff is exponential and capped at 30s`() {
        val expectedBase = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        expectedBase.forEachIndexed { index, base ->
            val attempt = index + 1
            val delay = computeReconnectDelayMs(attempt)
            // Full jitter: [0.5, 1.5) of the base, then capped at 30s.
            val min = (base * 0.5).toLong()
            val max = minOf((base * 1.5).toLong(), 30_000L) + 1L
            assertTrue(
                "attempt $attempt delay $delay outside [$min, $max)",
                delay in min until max,
            )
        }
    }

    @Test
    fun `backoff never exceeds 30s even for huge attempt counts`() {
        listOf(10, 20, 100, 10_000).forEach { attempt ->
            val delay = computeReconnectDelayMs(attempt)
            assertTrue("attempt $attempt delay $delay exceeds cap", delay <= 30_000L)
            assertTrue("attempt $attempt delay $delay must stay positive", delay > 0L)
        }
    }

    @Test
    fun `a connect attempt does not cancel the reconnect loop running it`() =
        runTest {
            val client = newClient()
            val loop = CoroutineScope(coroutineContext).launch { awaitCancellation() }

            setReconnectJob(client, loop)
            client.prepareForConnectAttempt {}
            assertTrue(
                "prepareForConnectAttempt runs inside the loop's own attempt, so cancelling here " +
                    "would end the loop at its first retry and the client would never reconnect again",
                loop.isActive,
            )

            // A fresh, user-initiated connect is the one case that must supersede a pending loop.
            setReconnectJob(client, loop)
            client.disconnect {}
            assertTrue("a user-initiated disconnect must end the pending loop", loop.isCancelled)
        }

    @Test
    fun `reconnect stays pending through its offline wait until a disconnect ends it`() {
        // Offline: the loop parks in its connectivity wait, the state a backgrounded
        // drop sits in. The foreground service keys on this flag to stay up.
        val client = newClient(online = false)
        setField(client, "currentConfig", manualConfig())

        scheduleReconnect(client)
        assertTrue(client.reconnectPending.value)

        client.disconnect {}
        assertFalse(client.reconnectPending.value)
    }

    @Test
    fun `a reconnect loop with no config to retry does not stay pending`() {
        val client = newClient()

        scheduleReconnect(client)

        assertFalse(client.reconnectPending.value)
    }

    @Test
    fun `a rejected resume stops the loop and emits SessionResumeRejected`() =
        runTest {
            val events = mutableListOf<AcpManagerEvent>()
            val client =
                newClient(
                    scope = CoroutineScope(coroutineContext),
                    resume = { _, _ -> throw GatewaySessionResumeRejectedException("gateway restarted") },
                    emitEvent = { events += it },
                )
            setField(client, "currentConfig", gatewayConfig())

            scheduleReconnect(client)
            advanceUntilIdle()

            assertEquals(
                "the facade needs exactly one rejection notice to start a fresh connect",
                listOf(AcpManagerEvent.SessionResumeRejected),
                events,
            )
            assertFalse(
                "the loop must stop retrying a session the gateway no longer has",
                client.reconnectPending.value,
            )
        }

    private fun scheduleReconnect(client: AcpTransportClient) {
        val loop = reconnectLoopOf(client)
        val method = loop.javaClass.getDeclaredMethod("schedule", Function0::class.java)
        method.isAccessible = true
        method.invoke(loop, {})
    }

    private fun reconnectLoopOf(client: AcpTransportClient): Any {
        val field = client.javaClass.getDeclaredField("reconnect")
        field.isAccessible = true
        return checkNotNull(field.get(client))
    }

    private fun setField(
        client: AcpTransportClient,
        name: String,
        value: Any?,
    ) {
        val field = client.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(client, value)
    }

    private fun newClient(
        online: Boolean = true,
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
        resume: suspend (AcpConnectionConfig.Gateway, AcpConnectionConfig.ResumeInfo) -> String =
            { _, _ -> error("no gateway resume in this test") },
        emitEvent: suspend (AcpManagerEvent) -> Unit = {},
    ): AcpTransportClient =
        AcpTransportClient(
            connectivityObserver = ConnectivityObserverStub(initialState = online),
            resumeAttachToken = resume,
            scope = scope,
            diagnosticsStore = AcpDiagnosticsStore(),
            listener =
                object : AcpTransportListener {
                    override fun updateConnectionState(state: AcpConnectionState) = Unit

                    override suspend fun emitManagerEvent(event: AcpManagerEvent) = emitEvent(event)
                },
        )

    private fun manualConfig(): AcpConnectionConfig.Manual =
        AcpConnectionConfig.Manual(
            scheme = "ws",
            host = "192.168.1.5:5788",
            preferredAuthMethodId = null,
            displayName = null,
        )

    private fun gatewayConfig(
        sessionId: String = "sess-42",
        attachToken: String = "at-1",
    ): AcpConnectionConfig.Gateway =
        AcpConnectionConfig.Gateway(
            endpoint = "ws://192.168.1.5:5788",
            runtimeId = "rt-1",
            sourceId = "src-1",
            gatewayCredential = "cred-1",
            resume = AcpConnectionConfig.ResumeInfo(sessionId = sessionId, attachToken = attachToken),
            scheme = "http",
            host = "192.168.1.5:5788",
            preferredAuthMethodId = null,
            displayName = null,
        )

    private fun setReconnectJob(
        client: AcpTransportClient,
        job: Job,
    ) {
        val loop = reconnectLoopOf(client)
        val field = loop.javaClass.getDeclaredField("reconnectJob")
        field.isAccessible = true
        field.set(loop, job)
    }

    // ---- Diagnostics telemetry ----

    @Test
    fun `setReconnectAttempt records attempts and dedupes`() =
        runTest {
            val store = AcpDiagnosticsStore()
            val emissions = mutableListOf<ConnectionDiagnostics>()
            val collect =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    store.diagnostics.collect { emissions += it }
                }
            assertEquals(0, emissions.single().reconnectAttempts)

            store.setReconnectAttempt(1)
            assertEquals(1, emissions.last().reconnectAttempts)

            // A redundant set must be a true no-op: the diagnostics sheet re-reads the
            // whole state on every emission, so a backoff tick that repeats the attempt
            // count must not emit again.
            store.setReconnectAttempt(1)
            assertEquals("a redundant update must not emit", 2, emissions.size)

            store.setReconnectAttempt(2)
            assertEquals(2, emissions.last().reconnectAttempts)

            collect.cancel()
        }

    @Test
    fun `setReconnectAttempt ignores non-positive values`() {
        val store = AcpDiagnosticsStore()

        store.setReconnectAttempt(0)
        store.setReconnectAttempt(-1)

        assertEquals(0, store.diagnostics.value.reconnectAttempts)
    }

    @Test
    fun `markDisconnected and startConnect reset reconnect attempts`() =
        runTest {
            val store = AcpDiagnosticsStore()
            store.setReconnectAttempt(5)
            assertEquals(5, store.diagnostics.value.reconnectAttempts)

            store.markDisconnected()
            assertEquals(0, store.diagnostics.value.reconnectAttempts)

            store.setReconnectAttempt(3)
            store.startConnect("ws://example.com")
            assertEquals(0, store.diagnostics.value.reconnectAttempts)
        }
}
