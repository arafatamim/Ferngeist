package com.tamimarafat.ferngeist.acp.bridge.connection

import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.AuthMethod
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.WebSocketTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

internal class AcpTransportClient(
    private val connectivityObserver: ConnectivityObserver,
    private val gatewayRepository: GatewayRepository?,
    private val scope: CoroutineScope,
    private val diagnosticsStore: AcpDiagnosticsStore,
    private val updateConnectionState: (AcpConnectionState) -> Unit,
    private val emitManagerEvent: suspend (AcpManagerEvent) -> Unit,
) {
    companion object {
        private const val WEB_SOCKET_PING_INTERVAL_MILLIS = 20_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }

    private var reconnectJob: Job? = null
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
        return connectInternal(
            config = config,
            resetState = resetState,
            scheduleReconnectOnFailure = true,
        )
    }

    @OptIn(UnstableApi::class)
    suspend fun initialize(): AcpInitializeResult? {
        val client = sdkClient ?: return null
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "initialize")
            val info =
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
                                ),
                            implementation = Implementation(name = "Ferngeist", version = "1.0.0"),
                        ),
                )

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
            if (error is CancellationException) throw error
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
        reconnectJob?.cancel()
        reconnectJob = null
        resetState()
        closeTransport()
        updateConnectionState(AcpConnectionState.Disconnected)
        diagnosticsStore.markDisconnected()
        scope.launch { emitManagerEvent(AcpManagerEvent.Disconnected) }
    }

    fun prepareForConnectAttempt(resetState: () -> Unit) {
        reconnectJob?.cancel()
        reconnectJob = null
        resetState()
        closeTransport()
        diagnosticsStore.clearRuntimeState()
    }

    suspend fun awaitConnectivityForReconnect() {
        val currentlyOnline = runCatching { connectivityObserver.isConnected.first() }.getOrDefault(true)
        if (currentlyOnline) return

        reconnectAttempts = 0
        connectivityObserver.isConnected.first { it }
    }

    @OptIn(UnstableApi::class)
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
        val (wsUrl, diagnosticsUrl) = if (config.isResilientSession) {
            val sid = config.sessionId!!
            val att = config.attachToken!!
            val base = rawEndpointUrl.substringBefore('?')
            "$base?sessionId=$sid&attachToken=$att" to "$base?sessionId=$sid&attachToken=***"
        } else {
            rawEndpointUrl to rawEndpointUrl
        }
        return try {
            establishSession(
                wsUrl = wsUrl,
                bearerToken = if (config.isResilientSession) null else config.webSocketBearerToken,
                resetState = resetState,
                diagnosticsUrl = diagnosticsUrl,
            )
        } catch (error: Exception) {
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
            false
        }
    }

    private suspend fun connectSessionResume(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
        scheduleReconnectOnFailure: Boolean,
    ): Boolean {
        val sessionId = config.sessionId ?: return false
        val gatewayRepo = gatewayRepository ?: return false
        val gatewayScheme = config.gatewayScheme ?: return false
        val gatewayHost = config.gatewayHost ?: return false
        val gatewayCredential = config.gatewayCredential ?: return false

        try {
            val resumeResponse = gatewayRepo.resumeSession(
                scheme = gatewayScheme,
                host = gatewayHost,
                gatewayCredential = gatewayCredential,
                sessionId = sessionId,
            )
            currentConfig = config.copy(attachToken = resumeResponse.attachToken)
            val rawEndpointUrl = config.webSocketUrl ?: "${config.scheme}://${config.host}"
            val base = rawEndpointUrl.substringBefore('?')
            val wsUrl = "$base?sessionId=$sessionId&attachToken=${resumeResponse.attachToken}"
            val diagnosticsUrl = "$base?sessionId=$sessionId&attachToken=***"

            prepareForConnectAttempt(resetState)
            updateConnectionState(AcpConnectionState.Connecting)
            diagnosticsStore.setWebSocketState(WebSocketState.CONNECTING)

            return establishSession(
                wsUrl = wsUrl,
                bearerToken = null,
                resetState = resetState,
                diagnosticsUrl = diagnosticsUrl,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            updateConnectionState(AcpConnectionState.Failed(error))
            diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
            diagnosticsStore.appendError("connect-resume", formatAcpErrorMessage(error, "Session resume failed"))
            if (scheduleReconnectOnFailure) {
                scheduleReconnect(resetState)
            }
            return false
        }
    }

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
        val webSocketSession =
            acquireHttpClient().webSocketSession {
                url(wsUrl)
                bearerToken?.takeIf { it.isNotBlank() }?.let {
                    headers.append("Authorization", "Bearer $it")
                }
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
        protocol.start()

        sdkClient = Client(protocol)

        updateConnectionState(AcpConnectionState.Connected)
        diagnosticsStore.setWebSocketState(WebSocketState.OPEN)
        reconnectAttempts = 0
        scope.launch { emitManagerEvent(AcpManagerEvent.Connected) }
        return true
    }

    private fun scheduleReconnect(resetState: () -> Unit) {
        if (reconnectJob != null) return
        reconnectJob =
            scope.launch {
                val config = currentConfig ?: return@launch
                while (sdkClient == null) {
                    reconnectAttempts++
                    // Linear backoff with ±50% jitter to prevent thundering-herd reconnects
                    // when many clients retry against the same gateway. Capped at 30s.
                    val baseDelayMs = 1000L * reconnectAttempts
                    val jitteredDelayMs = (baseDelayMs * (0.5 + Random.nextDouble())).toLong()
                    delay(jitteredDelayMs.coerceAtMost(MAX_RECONNECT_DELAY_MS))

                    val reconnected = if (config.isResilientSession) {
                        connectSessionResume(config, resetState, scheduleReconnectOnFailure = false)
                    } else {
                        connectInternal(config, resetState, scheduleReconnectOnFailure = false)
                    }

                    if (reconnected) {
                        initialize()
                        break
                    }
                }
                reconnectJob = null
            }
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

        if (error == null || isCancellationLikeError(error)) {
            updateConnectionState(AcpConnectionState.Disconnected)
            diagnosticsStore.markDisconnected()
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
