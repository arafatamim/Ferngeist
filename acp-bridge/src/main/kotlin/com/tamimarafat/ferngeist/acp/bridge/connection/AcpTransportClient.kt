package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.tamimarafat.ferngeist.acp.bridge.connection.transport.AcpHandshake
import com.tamimarafat.ferngeist.acp.bridge.connection.transport.AcpSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Computes the delay before reconnect attempt [attempt] (1-based): exponential
 * base 1000 * 2^(attempt-1) ms with full jitter in [0.5, 1.5), capped at
 * [MAX_RECONNECT_DELAY_MS]. Pure and deterministic-bounded so tests can assert
 * the schedule without running real sleeps.
 */
internal fun computeReconnectDelayMs(attempt: Int): Long {
    val baseDelayMs =
        RECONNECT_BASE_DELAY_MS shl
            (attempt - 1).coerceAtMost(RECONNECT_MAX_SHIFT)
    val jitteredDelayMs = (baseDelayMs * (0.5 + Random.nextDouble())).toLong()
    return jitteredDelayMs.coerceAtMost(MAX_RECONNECT_DELAY_MS)
}

// Exponential backoff base: attempt N waits 1000 * 2^(N-1) ms before jitter,
// capped at MAX_RECONNECT_DELAY_MS. Sequence: 1s, 2s, 4s, 8s, 16s, 30s, 30s, …
// — snappy early retries, gentle long-term spacing.
private const val RECONNECT_BASE_DELAY_MS = 1_000L

// Upper bound for the exponent so 1000L shl exponent cannot overflow Long even
// after years of unattended retries. The final coerceAtMost in
// [computeReconnectDelayMs] is what actually caps the wait.
private const val RECONNECT_MAX_SHIFT = 30

// Ceiling for any single reconnect wait, independent of attempt count.
private const val MAX_RECONNECT_DELAY_MS = 30_000L

internal class AcpTransportClient(
    connectivityObserver: ConnectivityObserver,
    // Mints a fresh attachToken for a gateway session — the gateway resume call, in
    // production. The transport holds this instead of the repository: resume is the only
    // gateway call it ever made, and its caller already owns a repository.
    private val resumeAttachToken: suspend (
        gateway: AcpConnectionConfig.Gateway,
        session: AcpConnectionConfig.ResumeInfo,
    ) -> String,
    private val scope: CoroutineScope,
    private val diagnosticsStore: AcpDiagnosticsStore,
    private val listener: AcpTransportListener,
) {
    private val reconnect =
        ReconnectLoop(
            scope = scope,
            connectivityObserver = connectivityObserver,
            diagnosticsStore = diagnosticsStore,
            isLive = { sdkClient != null },
            currentConfig = { currentConfig },
            attemptOnce = ::reconnectAndRestore,
        )

    /** True while a background reconnect loop is armed, including its backoff waits. */
    val reconnectPending: StateFlow<Boolean> = reconnect.pending

    // Set while the reconnect loop owns the attempt: Connected then waits for the
    // handshake (and session restore) instead of firing when the socket opens.
    private var deferConnectedState = false
    private var currentConfig: AcpConnectionConfig? = null

    private val socket =
        AcpSocket(
            scope = scope,
            listener = listener,
            diagnosticsStore = diagnosticsStore,
            onTermination = ::handleUnexpectedTransportTermination,
            onConnected = { if (!deferConnectedState) announceConnected() },
        )
    val handshake =
        AcpHandshake(
            currentClient = { socket.client },
            diagnosticsStore = diagnosticsStore,
            scope = scope,
            listener = listener,
            onAuthenticated = { methodId -> currentConfig = currentConfig?.withPreferredAuthMethodId(methodId) },
        )
    val sdkClient: Client? get() = socket.client

    val currentConnectionConfig: AcpConnectionConfig? get() = currentConfig

    suspend fun connect(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
    ): Boolean {
        currentConfig = config
        cancelReconnectLoop()
        return connectInternal(
            config = config,
            resetState = resetState,
            scheduleReconnectOnFailure = true,
        )
    }

    suspend fun connectWithoutReconnect(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
    ): Boolean {
        currentConfig = config
        cancelReconnectLoop()
        return connectInternal(
            config = config,
            resetState = resetState,
            scheduleReconnectOnFailure = false,
        )
    }

    fun disconnect(resetState: () -> Unit) {
        cancelReconnectLoop()
        resetState()
        socket.close()
        listener.updateConnectionState(AcpConnectionState.Disconnected)
        diagnosticsStore.markDisconnected()
        scope.launch { listener.emitManagerEvent(AcpManagerEvent.Disconnected) }
    }

    /**
     * Clears transport state ahead of a connect attempt.
     *
     * Deliberately does NOT cancel a pending reconnect loop: this runs inside the
     * loop's own attempt, so cancelling here would end the loop at the first
     * suspension of its first retry — the client would then never reconnect again
     * for the life of the process. Superseding a pending loop belongs to the
     * user-initiated entries ([connect], [connectWithoutReconnect]) and [disconnect].
     */
    fun prepareForConnectAttempt(resetState: () -> Unit) {
        resetState()
        socket.close()
        diagnosticsStore.clearRuntimeState()
    }

    /** Cancels a pending reconnect loop so a fresh connect supersedes it. */
    private fun cancelReconnectLoop() {
        reconnect.cancel()
        // Reset here, not in the loop's finally: a cancelled loop finishes after its
        // replacement started, and would clear the flag under the new attempt.
        deferConnectedState = false
    }

    /**
     * Writes a ping on the open socket. After the process was frozen or the network
     * changed, the socket can still read Connected while its peer is gone; a write
     * draws the reset at once, where the next scheduled ping could be 15s away.
     * The reset surfaces through the transport's onError/onClose and the reconnect
     * path. A live peer just answers with a pong, which the session drops.
     */
    fun probe() {
        socket.probe()
    }

    @OptIn(UnstableApi::class)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun connectInternal(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
        scheduleReconnectOnFailure: Boolean,
    ): Boolean {
        prepareForConnectAttempt(resetState)
        listener.updateConnectionState(AcpConnectionState.Connecting)
        diagnosticsStore.setWebSocketState(WebSocketState.CONNECTING)

        // A gateway handoff always carries the session the gateway provisioned, so the
        // attach URL is built from it. There is no bearer path: the gateway authenticates
        // the attach with the attachToken in the URL, and the device credential covers the
        // REST calls.
        val resume = (config as? AcpConnectionConfig.Gateway)?.resume
        val (wsUrl, diagnosticsUrl) =
            if (resume != null) {
                val base = config.endpoint.substringBefore('?')
                "$base?sessionId=${resume.sessionId}&attachToken=${resume.attachToken}" to
                    "$base?sessionId=${resume.sessionId}&attachToken=***"
            } else {
                config.endpoint to config.endpoint
            }
        val wsResult =
            runCatching {
                socket.establishSession(
                    wsUrl = wsUrl,
                    resetState = resetState,
                    diagnosticsUrl = diagnosticsUrl,
                )
            }
        return wsResult.fold(
            onSuccess = { it },
            onFailure = { error ->
                handleEstablishFailure(
                    error = error,
                    config = config,
                    resetState = resetState,
                    scheduleReconnectOnFailure = scheduleReconnectOnFailure,
                )
            },
        )
    }

    /**
     * Routes a failed WebSocket handshake to the appropriate recovery path:
     * session-resume on a 409 conflict, silent disconnect on cancellation-like
     * errors, otherwise Failed state with optional reconnect.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun handleEstablishFailure(
        error: Throwable,
        config: AcpConnectionConfig,
        resetState: () -> Unit,
        scheduleReconnectOnFailure: Boolean,
    ): Boolean {
        // Broad catch: the WebSocket handshake + ACP protocol init can surface any
        // of IOException, SDK protocol errors, or timeout conversions; every failure
        // type must route to the same Failed-state + reconnect path (a reconnect
        // loop must never die on an unexpected exception type).
        if (error is CancellationException) throw error
        // 409 means the gateway has an active resilient session for this runtime — the
        // attachToken the handoff carried is not enough; resume must run first to migrate
        // the live session to this client. The gateway's attach-conflict semantics are
        // unchanged in v2, so this recovery stays.
        val gateway = config as? AcpConnectionConfig.Gateway
        if (gateway != null && isWebSocketConflictError(error)) {
            return connectSessionResume(gateway, resetState, scheduleReconnectOnFailure)
        }
        if (isCancellationLikeError(error)) {
            listener.updateConnectionState(AcpConnectionState.Disconnected)
            diagnosticsStore.markDisconnected()
            return false
        }
        listener.updateConnectionState(AcpConnectionState.Failed(error))
        diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
        diagnosticsStore.appendError("connect", formatAcpErrorMessage(error, "Unknown connection failure"))
        if (scheduleReconnectOnFailure) {
            reconnect.schedule(resetState)
        }
        return false
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun connectSessionResume(
        gateway: AcpConnectionConfig.Gateway,
        resetState: () -> Unit,
        scheduleReconnectOnFailure: Boolean,
    ): Boolean {
        val result =
            runCatching {
                resumeAndEstablishSession(
                    gateway = gateway,
                    resume = gateway.resume,
                    resetState = resetState,
                )
            }
        return result.fold(
            onSuccess = { it },
            onFailure = { error -> onResumeFailure(error, resetState, scheduleReconnectOnFailure) },
        )
    }

    private suspend fun onResumeFailure(
        error: Throwable,
        resetState: () -> Unit,
        scheduleReconnectOnFailure: Boolean,
    ): Boolean {
        // Broad catch: gateway resume is a network boundary; any failure type must
        // surface as Failed + optional reconnect rather than killing the coroutine.
        if (error is CancellationException) throw error
        listener.updateConnectionState(AcpConnectionState.Failed(error))
        diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
        diagnosticsStore.appendError("connect-resume", formatAcpErrorMessage(error, "Session resume failed"))
        // 400 means the session is gone (the gateway restarted, or its agent failed).
        // Retrying resume can never succeed: stop the loop and tell the facade to start
        // a fresh connect instead of scheduling another attempt.
        if (error is GatewaySessionResumeRejectedException) {
            reconnect.markResumeRejected()
            listener.emitManagerEvent(AcpManagerEvent.SessionResumeRejected)
        } else if (scheduleReconnectOnFailure) {
            reconnect.schedule(resetState)
        }
        return false
    }

    /** Mints a fresh attachToken via gateway resume, then attaches the WebSocket. */
    private suspend fun resumeAndEstablishSession(
        gateway: AcpConnectionConfig.Gateway,
        resume: AcpConnectionConfig.ResumeInfo,
        resetState: () -> Unit,
    ): Boolean {
        val attachToken = resumeAttachToken(gateway, resume)
        currentConfig = gateway.copy(resume = resume.copy(attachToken = attachToken))
        val base = gateway.endpoint.substringBefore('?')
        val wsUrl = "$base?sessionId=${resume.sessionId}&attachToken=$attachToken"
        val diagnosticsUrl = "$base?sessionId=${resume.sessionId}&attachToken=***"

        prepareForConnectAttempt(resetState)
        listener.updateConnectionState(AcpConnectionState.Connecting)
        diagnosticsStore.setWebSocketState(WebSocketState.CONNECTING)

        return socket.establishSession(
            wsUrl = wsUrl,
            resetState = resetState,
            diagnosticsUrl = diagnosticsUrl,
        )
    }

    private fun announceConnected() {
        listener.updateConnectionState(AcpConnectionState.Connected)
        diagnosticsStore.setWebSocketState(WebSocketState.OPEN)
        diagnosticsStore.setReconnectAttempt(0)
        reconnect.resetAttempts()
        scope.launch { listener.emitManagerEvent(AcpManagerEvent.Connected) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun reconnectOnce(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
    ): Boolean {
        val gateway = config as? AcpConnectionConfig.Gateway
        return try {
            if (gateway != null) {
                connectSessionResume(gateway, resetState, scheduleReconnectOnFailure = false)
            } else {
                connectInternal(config, resetState, scheduleReconnectOnFailure = false)
            }
        } catch (error: Throwable) {
            // A reconnect attempt must never crash the app or kill the loop:
            // network drops (ConnectException), handshake failures, and
            // gateway HTTP errors all surface here. Log + retry with backoff.
            if (error is CancellationException) throw error
            diagnosticsStore.appendError("reconnect", formatAcpErrorMessage(error, "Reconnect failed"))
            false
        }
    }

    /**
     * The transport half of one reconnect attempt, run by [ReconnectLoop] after its backoff:
     * reconnects, re-initializes and re-authenticates. Returns true when the socket is
     * connected and usable.
     */
    private suspend fun reconnectAndRestore(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
    ): Boolean {
        deferConnectedState = true
        if (reconnectOnce(config, resetState)) {
            val initialized = handshake.initialize()
            // ACP auth state is per-connection: a fresh socket after reconnect
            // has no authenticated session, so re-run authentication with the
            // stored preferred method before session ops resume.
            val authenticated =
                currentConfig?.preferredAuthMethodId?.let { methodId ->
                    handshake.authenticate(methodId)
                }
            // An open socket is not a usable connection. Breaking out here
            // regardless left the state Connected, and every reuse guard reads
            // that as "already connected and initialized" (ServerListViewModel's
            // reuse guard, ChatConnectionHub's listing/delete legs): session ops
            // then ran on a client whose handshake never completed, and their
            // failure was recorded as an empty result. Drop the socket and let
            // the loop retry with backoff instead.
            if (initialized != null && authenticated !is AcpAuthenticateResult.Failure) {
                // Announced only now: Connected sent the facade straight into
                // session/load while initialize was still in flight, and a
                // failed handshake then closed the socket under that load.
                // Sessions are restored first, so whoever reacts to Connected
                // finds them registered instead of loading them a second time.
                listener.onReconnected(initialized)
                announceConnected()
                return true
            }
            socket.discardClient()
            listener.updateConnectionState(AcpConnectionState.Disconnected)
            diagnosticsStore.markDisconnected()
        }
        return false
    }

    private suspend fun handleUnexpectedTransportTermination(
        resetState: () -> Unit,
        error: Throwable? = null,
    ) {
        reconnect.abandon()
        resetState()
        socket.release()

        if (error == null || isCancellationLikeError(error)) {
            listener.updateConnectionState(AcpConnectionState.Disconnected)
            diagnosticsStore.markDisconnected()
            // A clean onClose (error == null) right after a successful connect
            // is the gateway dropping us — usually an auth or session conflict.
            // Surface it so the UI can show a real error instead of a silent
            // "Disconnected" pill.
            if (error == null && currentConfig != null) {
                diagnosticsStore.appendError(
                    "connection",
                    "Connection closed by the server before any session activity.",
                )
            }
        } else {
            listener.updateConnectionState(AcpConnectionState.Failed(error))
            diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
            diagnosticsStore.appendError("connection", formatAcpErrorMessage(error, "Connection lost"))
        }
        listener.emitManagerEvent(AcpManagerEvent.Disconnected)
        reconnect.schedule(resetState)
    }
}

private fun AcpConnectionConfig.withPreferredAuthMethodId(methodId: String): AcpConnectionConfig =
    when (this) {
        is AcpConnectionConfig.Manual -> copy(preferredAuthMethodId = methodId)
        is AcpConnectionConfig.Gateway -> copy(preferredAuthMethodId = methodId)
    }
