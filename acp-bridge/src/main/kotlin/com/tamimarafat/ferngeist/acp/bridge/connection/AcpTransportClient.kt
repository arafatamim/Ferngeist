package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.AuthMethod
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.WebSocketTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class AcpTransportClient(
    private val connectivityObserver: ConnectivityObserver,
    private val scope: CoroutineScope,
    private val diagnosticsStore: AcpDiagnosticsStore,
    private val updateConnectionState: (AcpConnectionState) -> Unit,
    private val emitManagerEvent: suspend (AcpManagerEvent) -> Unit,
) {
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var currentConfig: AcpConnectionConfig? = null

    private var httpClient: HttpClient? = null
    private var activeTransportGeneration: Long = 0L
    private var ignoreTransportCallbacks = false
    var sdkClient: Client? = null
        private set

    fun currentConnectionConfig(): AcpConnectionConfig? = currentConfig

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

    suspend fun initialize(): AcpInitializeResult? {
        val client = sdkClient ?: return null
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "initialize")
            val info = client.initialize(
                clientInfo = ClientInfo(
                    capabilities = ClientCapabilities(
                        fs = FileSystemCapability(
                            readTextFile = false,
                            writeTextFile = false,
                        )
                    ),
                    implementation = Implementation(name = "Ferngeist", version = "1.0.0"),
                )
            )

            val mapped = AgentInfo(
                name = info.implementation?.name?.takeIf { it.isNotBlank() } ?: "Agent",
                version = info.implementation?.version?.takeIf { it.isNotBlank() } ?: "unknown",
            )
            val agentCapabilities = mapAgentCapabilities(info.capabilities)
            val authMethods = info.authMethods.map(::mapAuthMethod)
            val result = AcpInitializeResult.Ready(
                agentInfo = mapped,
                agentCapabilities = agentCapabilities,
                authMethods = authMethods,
            )

            diagnosticsStore.recordInitialization(mapped)
            scope.launch { emitManagerEvent(AcpManagerEvent.Initialized(result)) }
            result
        }.getOrElse { error ->
            diagnosticsStore.appendError("initialize", formatAcpErrorMessage(error, "Initialization failed"))
            scope.launch { emitManagerEvent(AcpManagerEvent.Error(error)) }
            null
        }
    }

    suspend fun authenticate(methodId: String): AcpAuthenticateResult {
        val client = sdkClient ?: return AcpAuthenticateResult.Failure("Authentication is unavailable because the server connection is closed.")
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "authenticate")
            client.authenticate(AuthMethodId(methodId))
            currentConfig = currentConfig?.copy(preferredAuthMethodId = methodId)
            scope.launch { emitManagerEvent(AcpManagerEvent.Authenticated(methodId)) }
            AcpAuthenticateResult.Success
        }.getOrElse { error ->
            val message = formatAcpErrorMessage(error, "Authentication failed")
            diagnosticsStore.appendError("authenticate", message)
            scope.launch { emitManagerEvent(AcpManagerEvent.Error(error)) }
            AcpAuthenticateResult.Failure(message)
        }
    }

    suspend fun disconnect(resetState: () -> Unit) {
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

    private suspend fun connectInternal(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
        scheduleReconnectOnFailure: Boolean,
    ): Boolean {
        prepareForConnectAttempt(resetState)
        updateConnectionState(AcpConnectionState.Connecting)
        diagnosticsStore.setWebSocketState(WebSocketState.CONNECTING)

        return try {
            val endpointUrl = config.webSocketUrl ?: "${config.scheme}://${config.host}"
            diagnosticsStore.startConnect(endpointUrl)

            val client = HttpClient(CIO) {
                install(WebSockets)
            }
            val webSocketSession = client.webSocketSession {
                url(endpointUrl)
                config.webSocketBearerToken?.takeIf { it.isNotBlank() }?.let {
                    headers.append("Authorization", "Bearer $it")
                }
            }
            val transport = WebSocketTransport(
                parentScope = webSocketSession,
                wss = webSocketSession,
            )
            val protocol = Protocol(
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

            httpClient = client
            sdkClient = Client(protocol)

            updateConnectionState(AcpConnectionState.Connected)
            diagnosticsStore.setWebSocketState(WebSocketState.OPEN)
            reconnectAttempts = 0
            scope.launch { emitManagerEvent(AcpManagerEvent.Connected) }
            true
        } catch (error: Exception) {
            updateConnectionState(AcpConnectionState.Failed(error))
            diagnosticsStore.setWebSocketState(WebSocketState.FAILED)
            diagnosticsStore.appendError("connect", formatAcpErrorMessage(error, "Unknown connection failure"))
            if (scheduleReconnectOnFailure) {
                scheduleReconnect(resetState)
            }
            false
        }
    }

    private fun scheduleReconnect(resetState: () -> Unit) {
        if (reconnectJob != null) return
        reconnectJob = scope.launch {
            val config = currentConfig ?: return@launch
            while (sdkClient == null) {
                awaitConnectivityForReconnect()
                reconnectAttempts++
                delay((1000L * reconnectAttempts).coerceAtMost(5000L))
                if (connectInternal(config, resetState, scheduleReconnectOnFailure = false)) {
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
        runCatching { httpClient?.close() }
        httpClient = null

        if (error == null) {
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

        runCatching { httpClient?.close() }
        httpClient = null
        ignoreTransportCallbacks = false
    }

    private fun mapAuthMethod(method: AuthMethod): AcpAuthMethodInfo {
        return when (method) {
            is AuthMethod.AgentAuth -> AcpAuthMethodInfo(
                id = method.id.toString(),
                name = method.name,
                description = method.description,
                type = "agent",
            )

            is AuthMethod.EnvVarAuth -> AcpAuthMethodInfo(
                id = method.id.toString(),
                name = method.name,
                description = method.description,
                type = "env",
                envVars = method.vars.map { variable ->
                    AuthEnvVarInfo(
                        name = variable.name,
                        label = variable.label,
                        secret = variable.secret,
                        optional = variable.optional,
                    )
                },
                link = method.link,
            )

            is AuthMethod.TerminalAuth -> AcpAuthMethodInfo(
                id = method.id.toString(),
                name = method.name,
                description = method.description,
                type = "terminal",
                args = method.args ?: emptyList(),
                env = method.env ?: emptyMap(),
            )

            is AuthMethod.UnknownAuthMethod -> AcpAuthMethodInfo(
                id = method.id.toString(),
                name = method.name,
                description = method.description,
                type = method.type,
            )
        }
    }

    private fun mapAgentCapabilities(capabilities: AgentCapabilities): AcpAgentCapabilities {
        return AcpAgentCapabilities(
            loadSession = capabilities.loadSession,
            prompt = mapPromptCapabilities(capabilities.promptCapabilities),
            mcp = mapMcpCapabilities(capabilities.mcpCapabilities),
            session = mapSessionCapabilities(capabilities.sessionCapabilities),
        )
    }

    private fun mapPromptCapabilities(capabilities: PromptCapabilities): AcpPromptCapabilities {
        return AcpPromptCapabilities(
            audio = capabilities.audio,
            embeddedContext = capabilities.embeddedContext,
            image = capabilities.image,
        )
    }

    private fun mapMcpCapabilities(capabilities: McpCapabilities): AcpMcpCapabilities {
        return AcpMcpCapabilities(
            http = capabilities.http,
            sse = capabilities.sse,
        )
    }

    private fun mapSessionCapabilities(capabilities: SessionCapabilities): AcpSessionCapabilities {
        return AcpSessionCapabilities(
            fork = capabilities.fork != null,
            list = capabilities.list != null,
            resume = capabilities.resume != null,
        )
    }
}
