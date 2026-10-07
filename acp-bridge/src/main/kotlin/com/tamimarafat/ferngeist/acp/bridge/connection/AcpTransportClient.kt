package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.AuthMethod
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.ElicitationFormCapabilities
import com.agentclientprotocol.model.ElicitationUrlCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.WebSocketTransport
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
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

// Keeps the WebSocket alive and detects dead peers: the CIO client pings on
// this interval when no frames are exchanged.
private const val WEB_SOCKET_PING_INTERVAL_MILLIS = 15_000L

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

// Bounds the WebSocket upgrade handshake. Without this, a peer that accepts
// the TCP socket but never completes the upgrade (or a dead agent behind a
// gateway) parks connect() forever — the initialize()/listSessions() timeouts
// never get reached because they run after the handshake.
private const val WEB_SOCKET_CONNECT_TIMEOUT_MS = 30_000L

internal class AcpTransportClient(
    private val connectivityObserver: ConnectivityObserver,
    private val gatewayRepository: GatewayRepository?,
    private val scope: CoroutineScope,
    private val diagnosticsStore: AcpDiagnosticsStore,
    private val updateConnectionState: (AcpConnectionState) -> Unit,
    private val emitManagerEvent: suspend (AcpManagerEvent) -> Unit,
    // Runs after a background reconnect's handshake, before Connected is announced.
    private val onReconnected: suspend (AcpInitializeResult) -> Unit = {},
    private val onRemoteTurnEnded: suspend (sessionId: String, stopReason: String) -> Unit = { _, _ -> },
) {
    private var reconnectJob: Job? = null

    // A reconnect loop is armed. Between attempts the state reads Disconnected/Failed,
    // yet the connection is not idle: the foreground service must outlive the backoff,
    // or the cached process is frozen and the loop never gets to run.
    private val _reconnectPending = MutableStateFlow(false)
    val reconnectPending: StateFlow<Boolean> = _reconnectPending.asStateFlow()

    // Set while the reconnect loop owns the attempt: Connected then waits for the
    // handshake (and session restore) instead of firing when the socket opens.
    private var deferConnectedState = false
    private var activeWebSocket: DefaultClientWebSocketSession? = null
    private var reconnectAttempts = 0
    private var currentConfig: AcpConnectionConfig? = null

    // Reused across all connect/reconnect attempts. CIO's HttpClient is heavyweight
    // (coroutine machinery, thread pools, websocket engine), so creating one per
    // session is wasteful. Initialized lazily on first use; nulled by [close].
    private var sharedHttpClient: HttpClient? = null

    private var activeTransportGeneration: Long = 0L
    private var ignoreTransportCallbacks = false
    var sdkClient: Client? = null
        private set

    fun currentConnectionConfig(): AcpConnectionConfig? = currentConfig

    /**
     * Tears down the shared [HttpClient]. Intended for graceful shutdown of the
     * owning connection manager; not called on normal disconnects (the client
     * is reused across reconnects). Idempotent.
     */
    fun close() {
        val client = sharedHttpClient ?: return
        sharedHttpClient = null
        runCatching { client.close() }
    }

    /** Returns the shared [HttpClient], creating it on first call. */
    private fun acquireHttpClient(): HttpClient =
        sharedHttpClient
            ?: HttpClient(CIO) {
                install(WebSockets) {
                    pingIntervalMillis = WEB_SOCKET_PING_INTERVAL_MILLIS
                }
            }.also { sharedHttpClient = it }

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

    @OptIn(UnstableApi::class)
    suspend fun initialize(): AcpInitializeResult? {
        val client = sdkClient ?: return null
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "initialize")
            val info =
                withTimeout(30_000L) {
                    client.initialize(
                        clientInfo =
                            ClientInfo(
                                capabilities =
                                    ClientCapabilities(
                                        fs =
                                            FileSystemCapability(
                                                readTextFile = false,
                                                writeTextFile = false,
                                            ),
                                        // Form mode renders a schema-driven sheet; URL mode shows
                                        // the target host and opens it in the system browser
                                        // on consent. Both must be explicit per spec: an
                                        // omitted mode reads as unsupported to the agent.
                                        elicitation =
                                            ElicitationCapabilities(
                                                form = ElicitationFormCapabilities(),
                                                url = ElicitationUrlCapabilities(),
                                            ),
                                    ),
                                implementation = Implementation(name = "Ferngeist", version = "1.0.0"),
                            ),
                    )
                }

            val mapped =
                AgentInfo(
                    name = info.implementation?.name?.takeIf { it.isNotBlank() } ?: "Agent",
                    version = info.implementation?.version?.takeIf { it.isNotBlank() } ?: "unknown",
                )
            val authMethods = info.authMethods.map(::mapAuthMethod)
            val result =
                AcpInitializeResult.Ready(
                    agentInfo = mapped,
                    agentCapabilities = info.capabilities,
                    authMethods = authMethods,
                )

            diagnosticsStore.recordInitialization(mapped)
            scope.launch { emitManagerEvent(AcpManagerEvent.Initialized(result)) }
            result
        }.getOrElse { error ->
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            diagnosticsStore.appendError("initialize", formatAcpErrorMessage(error, "Initialization failed"))
            scope.launch { emitManagerEvent(AcpManagerEvent.Error(error)) }
            null
        }
    }

    suspend fun authenticate(methodId: String): AcpAuthenticateResult {
        val client =
            sdkClient
                ?: return AcpAuthenticateResult.Failure(
                    "Authentication is unavailable because the server connection is closed.",
                )
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "authenticate")
            client.authenticate(AuthMethodId(methodId))
            currentConfig = currentConfig?.copy(preferredAuthMethodId = methodId)
            scope.launch { emitManagerEvent(AcpManagerEvent.Authenticated(methodId)) }
            AcpAuthenticateResult.Success
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val message = formatAcpErrorMessage(error, "Authentication failed")
            diagnosticsStore.appendError("authenticate", message)
            scope.launch { emitManagerEvent(AcpManagerEvent.Error(error)) }
            AcpAuthenticateResult.Failure(message)
        }
    }

    fun disconnect(resetState: () -> Unit) {
        cancelReconnectLoop()
        resetState()
        closeTransport()
        updateConnectionState(AcpConnectionState.Disconnected)
        diagnosticsStore.markDisconnected()
        scope.launch { emitManagerEvent(AcpManagerEvent.Disconnected) }
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
        closeTransport()
        diagnosticsStore.clearRuntimeState()
    }

    /** Cancels a pending reconnect loop so a fresh connect supersedes it. */
    private fun cancelReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectPending.value = false
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
        activeWebSocket?.outgoing?.trySend(Frame.Ping(ByteArray(0)))
    }

    suspend fun awaitConnectivityForReconnect() {
        // A connectivity read that fails is not evidence that the device is online:
        // assuming "online" skips the wait and spends a reconnect attempt while offline.
        val currentlyOnline = runCatching { connectivityObserver.isConnected.first() }.getOrDefault(false)
        if (currentlyOnline) return

        reconnectAttempts = 0
        connectivityObserver.isConnected.first { it }
    }

    @OptIn(UnstableApi::class)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun connectInternal(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
        scheduleReconnectOnFailure: Boolean,
    ): Boolean {
        prepareForConnectAttempt(resetState)
        updateConnectionState(AcpConnectionState.Connecting)
        diagnosticsStore.setWebSocketState(WebSocketState.CONNECTING)

        val rawEndpointUrl = config.webSocketUrl ?: "${config.scheme}://${config.host}"
        // A gateway agent is always launched in resilient mode, so a gateway connection
        // must carry a sessionId. If it does not, the gateway could not provision a
        // session (the agent crashed, its runtime lease is held, or the device hit its
        // session limit) and returned a session-less connect descriptor. The resilient
        // ACP endpoint rejects a connect without sessionId/attachToken with HTTP 400, so
        // attempting it here would loop on a doomed handshake. Fail fast with an
        // actionable error instead; the caller's retry rebuilds a fresh handoff.
        if (config.isGatewayConnection && config.sessionId == null) {
            val error =
                IllegalStateException(
                    "The gateway could not start a session for this agent. " +
                        "It may have crashed or reached its session limit — try again, " +
                        "or restart the agent from the server list.",
                )
            updateConnectionState(AcpConnectionState.Failed(error))
            diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
            diagnosticsStore.appendError("connect", error.message ?: "Gateway session unavailable")
            return false
        }
        // Resilient config without an attachToken — skip the direct WebSocket connect
        // and route straight to resume, which mints a fresh attachToken.
        if (config.isResilientSession && config.attachToken == null) {
            return connectSessionResume(config, resetState, scheduleReconnectOnFailure)
        }
        val (wsUrl, diagnosticsUrl) =
            if (config.isResilientSession) {
                val sid = config.sessionId!!
                val att = config.attachToken!!
                val base = rawEndpointUrl.substringBefore('?')
                "$base?sessionId=$sid&attachToken=$att" to "$base?sessionId=$sid&attachToken=***"
            } else {
                rawEndpointUrl to rawEndpointUrl
            }
        val wsResult =
            runCatching {
                establishSession(
                    wsUrl = wsUrl,
                    bearerToken = if (config.isResilientSession) null else config.webSocketBearerToken,
                    resetState = resetState,
                    diagnosticsUrl = diagnosticsUrl,
                )
            }
        return if (wsResult.isSuccess) {
            wsResult.getOrThrow()
        } else {
            handleEstablishFailure(
                error = wsResult.exceptionOrNull()!!,
                config = config,
                resetState = resetState,
                scheduleReconnectOnFailure = scheduleReconnectOnFailure,
            )
        }
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
        // attachToken from connectRuntime is not enough; we must call resumeSession first
        // to explicitly migrate the live session to this client.
        if (config.isResilientSession && isWebSocketConflictError(error)) {
            return connectSessionResume(config, resetState, scheduleReconnectOnFailure)
        }
        if (isCancellationLikeError(error)) {
            updateConnectionState(AcpConnectionState.Disconnected)
            diagnosticsStore.markDisconnected()
            return false
        }
        updateConnectionState(AcpConnectionState.Failed(error))
        diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
        diagnosticsStore.appendError("connect", formatAcpErrorMessage(error, "Unknown connection failure"))
        if (scheduleReconnectOnFailure) {
            scheduleReconnect(resetState)
        }
        return false
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun connectSessionResume(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
        scheduleReconnectOnFailure: Boolean,
    ): Boolean {
        val resumeParams = resolveResumeParams(config) ?: return false

        val result =
            runCatching {
                resumeAndEstablishSession(
                    params = resumeParams,
                    config = config,
                    resetState = resetState,
                )
            }
        return if (result.isSuccess) {
            result.getOrThrow()
        } else {
            val error = result.exceptionOrNull()!!
            // Broad catch: gateway resume is a network boundary; any failure type must
            // surface as Failed + optional reconnect rather than killing the coroutine.
            if (error is CancellationException) throw error
            updateConnectionState(AcpConnectionState.Failed(error))
            diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
            diagnosticsStore.appendError("connect-resume", formatAcpErrorMessage(error, "Session resume failed"))
            if (scheduleReconnectOnFailure) {
                scheduleReconnect(resetState)
            }
            false
        }
    }

    /** Mints a fresh attachToken via gateway resume, then attaches the WebSocket. */
    private suspend fun resumeAndEstablishSession(
        params: ResumeSessionParams,
        config: AcpConnectionConfig,
        resetState: () -> Unit,
    ): Boolean {
        val resumeResponse =
            params.gatewayRepo.resumeSession(
                scheme = params.gatewayScheme,
                host = params.gatewayHost,
                gatewayCredential = params.gatewayCredential,
                sessionId = params.sessionId,
            )
        currentConfig = config.copy(attachToken = resumeResponse.attachToken)
        val rawEndpointUrl = config.webSocketUrl ?: "${config.scheme}://${config.host}"
        val base = rawEndpointUrl.substringBefore('?')
        val wsUrl = "$base?sessionId=${params.sessionId}&attachToken=${resumeResponse.attachToken}"
        val diagnosticsUrl = "$base?sessionId=${params.sessionId}&attachToken=***"

        prepareForConnectAttempt(resetState)
        updateConnectionState(AcpConnectionState.Connecting)
        diagnosticsStore.setWebSocketState(WebSocketState.CONNECTING)

        return establishSession(
            wsUrl = wsUrl,
            bearerToken = null,
            resetState = resetState,
            diagnosticsUrl = diagnosticsUrl,
        )
    }

    /** Resolves the fields required for a gateway session resume, or null if any is absent. */
    private fun resolveResumeParams(config: AcpConnectionConfig): ResumeSessionParams? =
        buildResumeParamsOrNull(
            config.sessionId,
            gatewayRepository,
            config.gatewayScheme,
            config.gatewayHost,
            config.gatewayCredential,
        )

    private fun buildResumeParamsOrNull(
        sessionId: String?,
        gatewayRepo: GatewayRepository?,
        gatewayScheme: String?,
        gatewayHost: String?,
        gatewayCredential: String?,
    ): ResumeSessionParams? {
        if (listOf(sessionId, gatewayRepo, gatewayScheme, gatewayHost, gatewayCredential).any { it == null }) {
            return null
        }
        return ResumeSessionParams(
            sessionId = sessionId!!,
            gatewayRepo = gatewayRepo!!,
            gatewayScheme = gatewayScheme!!,
            gatewayHost = gatewayHost!!,
            gatewayCredential = gatewayCredential!!,
        )
    }

    private data class ResumeSessionParams(
        val sessionId: String,
        val gatewayRepo: GatewayRepository,
        val gatewayScheme: String,
        val gatewayHost: String,
        val gatewayCredential: String,
    )

    @OptIn(UnstableApi::class)
    private suspend fun establishSession(
        wsUrl: String,
        bearerToken: String?,
        resetState: () -> Unit,
        diagnosticsUrl: String = wsUrl,
    ): Boolean {
        diagnosticsStore.startConnect(diagnosticsUrl)

        // Reuse the shared HttpClient rather than spinning up a new CIO
        // engine + websocket plugin on every reconnect.
        //
        // The handshake is bounded by WEB_SOCKET_CONNECT_TIMEOUT_MS. The
        // TimeoutCancellationException is converted into a plain IOException here so
        // connectInternal's catch treats it as a real connection failure (it rethrows
        // bare CancellationExceptions, which would otherwise cancel the caller before
        // it can clear the connecting spinner).
        val webSocketSession =
            try {
                withTimeout(WEB_SOCKET_CONNECT_TIMEOUT_MS) {
                    acquireHttpClient().webSocketSession {
                        url(wsUrl)
                        bearerToken?.takeIf { it.isNotBlank() }?.let {
                            headers.append("Authorization", "Bearer $it")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Deliberately NOT chaining the TimeoutCancellationException as the cause:
                // isCancellationLikeError() scans the cause chain for CancellationException
                // and would otherwise downgrade this to a silent disconnect instead of a
                // surfaced failure with reconnect.
                throw IOException(
                    "Connection timed out after ${WEB_SOCKET_CONNECT_TIMEOUT_MS / 1000}s. " +
                        "The server accepted the connection but did not complete the handshake.",
                )
            }
        // NOTE: Manual transport/Protocol setup instead of the SDK's
        // HttpClient.acpProtocolOnClientWebSocket() because Protocol.transport
        // is private — we need onError/onClose for reconnection.
        // Switch to the extension if a future SDK exposes Protocol.transport publicly.
        val transport =
            WebSocketTransport(
                parentScope = webSocketSession,
                wss = webSocketSession,
            )
        val protocol =
            Protocol(
                parentScope = webSocketSession,
                transport = transport,
                options = ProtocolOptions(protocolDebugName = "FerngeistACP"),
            )
        val generation = activeTransportGeneration + 1L
        activeTransportGeneration = generation
        ignoreTransportCallbacks = false
        transport.onError { error ->
            scope.launch {
                handleUnexpectedTransportTermination(
                    generation = generation,
                    resetState = resetState,
                    error = error,
                )
            }
        }
        transport.onClose {
            scope.launch {
                handleUnexpectedTransportTermination(
                    generation = generation,
                    resetState = resetState,
                )
            }
        }
        protocol.onRemoteTurnEnded(onRemoteTurnEnded)
        protocol.start()

        sdkClient = Client(protocol)
        activeWebSocket = webSocketSession
        if (!deferConnectedState) announceConnected()
        return true
    }

    private fun announceConnected() {
        updateConnectionState(AcpConnectionState.Connected)
        diagnosticsStore.setWebSocketState(WebSocketState.OPEN)
        diagnosticsStore.setReconnectAttempt(0)
        reconnectAttempts = 0
        scope.launch { emitManagerEvent(AcpManagerEvent.Connected) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleReconnect(resetState: () -> Unit) {
        if (reconnectJob != null) return
        _reconnectPending.value = true
        // Lazy: assigned before it runs, so the finally's ownership check sees itself
        // even when the loop completes without suspending.
        reconnectJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val config = currentConfig ?: return@launch
                    while (sdkClient == null) {
                        // Wait until the device is actually online before spending a retry.
                        // This also resets the backoff counter when connectivity had dropped,
                        // so the reconnect (and the offline-queue flush it triggers) fires
                        // promptly once the network returns instead of idling out the 30s cap.
                        awaitConnectivityForReconnect()
                        reconnectAttempts++
                        diagnosticsStore.setReconnectAttempt(reconnectAttempts)
                        // Exponential backoff with full jitter to prevent thundering-herd
                        // reconnects when many clients retry against the same gateway.
                        // Capped at 30s; retries forever (no attempt cap).
                        delay(computeReconnectDelayMs(reconnectAttempts))

                        deferConnectedState = true
                        val reconnected =
                            try {
                                if (config.isResilientSession) {
                                    connectSessionResume(config, resetState, scheduleReconnectOnFailure = false)
                                } else {
                                    connectInternal(config, resetState, scheduleReconnectOnFailure = false)
                                }
                            } catch (error: Throwable) {
                                // A reconnect attempt must never crash the app or kill the loop:
                                // network drops (ConnectException), handshake failures, and
                                // gateway HTTP errors all surface here. Log + retry with backoff.
                                if (error is CancellationException) throw error
                                diagnosticsStore.appendError(
                                    "reconnect",
                                    formatAcpErrorMessage(error, "Reconnect failed"),
                                )
                                false
                            }

                        if (reconnected) {
                            val initialized = initialize()
                            // ACP auth state is per-connection: a fresh socket after reconnect
                            // has no authenticated session, so re-run authentication with the
                            // stored preferred method before session ops resume.
                            val authenticated =
                                currentConfig?.preferredAuthMethodId?.let { methodId ->
                                    authenticate(methodId)
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
                                onReconnected(initialized)
                                announceConnected()
                                break
                            }
                            runCatching { sdkClient?.protocol?.close() }
                            sdkClient = null
                            updateConnectionState(AcpConnectionState.Disconnected)
                            diagnosticsStore.markDisconnected()
                        }
                    }
                } finally {
                    // A transport drop mid-attempt cancels this loop and arms a new one;
                    // clearing the field unconditionally here would orphan that loop.
                    if (reconnectJob == currentCoroutineContext().job) {
                        reconnectJob = null
                        _reconnectPending.value = false
                    }
                }
            }
        reconnectJob?.start()
    }

    private suspend fun handleUnexpectedTransportTermination(
        generation: Long,
        resetState: () -> Unit,
        error: Throwable? = null,
    ) {
        if (generation != activeTransportGeneration || ignoreTransportCallbacks) return

        reconnectJob?.cancel()
        reconnectJob = null
        ignoreTransportCallbacks = true
        resetState()
        runCatching { sdkClient?.protocol?.close() }
        sdkClient = null
        activeWebSocket = null

        if (error == null || isCancellationLikeError(error)) {
            updateConnectionState(AcpConnectionState.Disconnected)
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
            updateConnectionState(AcpConnectionState.Failed(error))
            diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
            diagnosticsStore.appendError("connection", formatAcpErrorMessage(error, "Connection lost"))
        }
        emitManagerEvent(AcpManagerEvent.Disconnected)
        scheduleReconnect(resetState)
        ignoreTransportCallbacks = false
    }

    private fun closeTransport() {
        ignoreTransportCallbacks = true
        activeTransportGeneration++
        runCatching { sdkClient?.protocol?.close() }
        sdkClient = null
        activeWebSocket = null

        ignoreTransportCallbacks = false
    }

    @OptIn(UnstableApi::class)
    private fun mapAuthMethod(method: AuthMethod): AcpAuthMethodInfo =
        when (method) {
            is AuthMethod.AgentAuth ->
                AcpAuthMethodInfo(
                    id = method.id.toString(),
                    name = method.name,
                    description = method.description,
                    type = "agent",
                )

            is AuthMethod.EnvVarAuth ->
                AcpAuthMethodInfo(
                    id = method.id.toString(),
                    name = method.name,
                    description = method.description,
                    type = "env",
                    envVars =
                        method.vars.map { variable ->
                            AuthEnvVarInfo(
                                name = variable.name,
                                label = variable.label,
                                secret = variable.secret,
                                optional = variable.optional,
                            )
                        },
                    link = method.link,
                )

            is AuthMethod.TerminalAuth ->
                AcpAuthMethodInfo(
                    id = method.id.toString(),
                    name = method.name,
                    description = method.description,
                    type = "terminal",
                    args = method.args ?: emptyList(),
                    env = method.env ?: emptyMap(),
                )

            is AuthMethod.UnknownAuthMethod ->
                AcpAuthMethodInfo(
                    id = method.id.toString(),
                    name = method.name,
                    description = method.description,
                    type = method.type,
                )
        }
}
