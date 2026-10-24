package com.tamimarafat.ferngeist.acp.bridge.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The background reconnect loop of one [AcpTransportClient]: arming, connectivity waits,
 * exponential backoff, and the pending flag the foreground service reads. Each attempt's
 * transport work is supplied by the owner as [attemptOnce].
 */
internal class ReconnectLoop(
    private val scope: CoroutineScope,
    private val connectivityObserver: ConnectivityObserver,
    private val diagnosticsStore: AcpDiagnosticsStore,
    private val isLive: () -> Boolean,
    private val currentConfig: () -> AcpConnectionConfig?,
    private val attemptOnce: suspend (AcpConnectionConfig, () -> Unit) -> Boolean,
) {
    private var reconnectJob: Job? = null

    // Set when gateway resume answered 400: the session is gone for good, so the reconnect
    // loop must stop retrying and the facade must start a fresh connect. Cleared only by a
    // fresh user-initiated connect ([cancelReconnectLoop]).
    private var resumeRejected = false

    // A reconnect loop is armed. Between attempts the state reads Disconnected/Failed,
    // yet the connection is not idle: the foreground service must outlive the backoff,
    // or the cached process is frozen and the loop never gets to run.
    private val pendingState = MutableStateFlow(false)

    private var reconnectAttempts = 0

    val pending: StateFlow<Boolean> = pendingState.asStateFlow()

    /** Cancels a pending reconnect loop so a fresh connect supersedes it. */
    fun cancel() {
        reconnectJob?.cancel()
        reconnectJob = null
        pendingState.value = false
        // A fresh user-initiated connect supersedes the resume rejection that stopped
        // the previous loop.
        resumeRejected = false
    }

    /** Drops the loop's job without touching the pending flag; the owner re-arms it next. */
    fun abandon() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun markResumeRejected() {
        resumeRejected = true
    }

    fun resetAttempts() {
        reconnectAttempts = 0
    }

    fun schedule(resetState: () -> Unit) {
        if (reconnectJob != null || resumeRejected) return
        pendingState.value = true
        // Lazy: assigned before it runs, so the finally's ownership check sees itself
        // even when the loop completes without suspending.
        reconnectJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val config = currentConfig() ?: return@launch
                    while (!isLive()) {
                        if (runReconnectAttempt(config, resetState)) break
                    }
                } finally {
                    // A transport drop mid-attempt cancels this loop and arms a new one;
                    // clearing the field unconditionally here would orphan that loop.
                    if (reconnectJob == currentCoroutineContext().job) {
                        reconnectJob = null
                        pendingState.value = false
                    }
                }
            }
        reconnectJob?.start()
    }

    /**
     * One reconnect attempt: waits for connectivity, backs off, then hands the transport work
     * to [attemptOnce]. Returns true when the loop must stop — either the resume was rejected
     * (the session is gone for good, so the facade's fresh connect supersedes this loop) or the
     * socket is connected and usable.
     */
    private suspend fun runReconnectAttempt(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
    ): Boolean {
        // A rejected resume (400) means this session is gone for good; the
        // facade's fresh connect supersedes this loop, so stop retrying.
        if (resumeRejected) return true
        // Wait until the device is actually online before spending a retry.
        // This also resets the backoff counter when connectivity had dropped,
        // so the reconnect (and the offline-queue flush it triggers) fires
        // promptly once the network returns instead of idling out the 30s cap.
        awaitConnectivity()
        reconnectAttempts++
        diagnosticsStore.setReconnectAttempt(reconnectAttempts)
        // Exponential backoff with full jitter to prevent thundering-herd
        // reconnects when many clients retry against the same gateway.
        // Capped at 30s; retries forever (no attempt cap).
        delay(computeReconnectDelayMs(reconnectAttempts))
        return attemptOnce(config, resetState)
    }

    private suspend fun awaitConnectivity() {
        // A connectivity read that fails is not evidence that the device is online:
        // assuming "online" skips the wait and spends a reconnect attempt while offline.
        val currentlyOnline = runCatching { connectivityObserver.isConnected.first() }.getOrDefault(false)
        if (currentlyOnline) return

        reconnectAttempts = 0
        connectivityObserver.isConnected.first { it }
    }
}
