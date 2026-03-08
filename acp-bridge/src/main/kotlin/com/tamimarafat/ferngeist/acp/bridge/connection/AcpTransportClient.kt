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
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.acpProtocolOnClientWebSocket
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
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
    var sdkClient: Client? = null
        private set

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
            // OpenCode and Claude Agent ACP currently advertises auth methods but can return
            // "Authentication not implemented" for authenticate. Bypass auth
            // for that agent specifically instead of weakening the general ACP flow.
            val bypassAdvertisedAuth = mapped.name == "OpenCode" || mapped.name == "@zed-industries/claude-agent-acp"
            val preferredMethodId = currentConfig
                ?.preferredAuthMethodId
                ?.takeIf { preferredId -> authMethods.any { it.id == preferredId } }
            val autoSelectedMethodId = preferredMethodId ?: authMethods.singleOrNull()?.id

            val result = if (authMethods.isEmpty() || bypassAdvertisedAuth) {
                AcpInitializeResult.Ready(
                    agentInfo = mapped,
                    agentCapabilities = agentCapabilities,
                    authMethods = authMethods,
                )
            } else if (autoSelectedMethodId != null) {
                diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "authenticate")
                client.authenticate(AuthMethodId(autoSelectedMethodId))
                AcpInitializeResult.Ready(
                    agentInfo = mapped,
                    agentCapabilities = agentCapabilities,
                    authMethods = authMethods,
                    authenticatedMethodId = autoSelectedMethodId,
                )
            } else {
                AcpInitializeResult.AuthenticationRequired(
                    agentInfo = mapped,
                    agentCapabilities = agentCapabilities,
                    authMethods = authMethods,
                )
            }

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
            val url = "${config.scheme}://${config.host}"
            diagnosticsStore.startConnect(url)

            val client = HttpClient(CIO) {
                install(WebSockets)
            }
            val protocolOptions = ProtocolOptions(protocolDebugName = "FerngeistACP")
            val wsProtocol = client.acpProtocolOnClientWebSocket(
                url = url,
                protocolOptions = protocolOptions,
            ) { }
            wsProtocol.start()

            httpClient = client
            sdkClient = Client(wsProtocol)

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

    private fun closeTransport() {
        runCatching { sdkClient?.protocol?.close() }
        sdkClient = null

        runCatching { httpClient?.close() }
        httpClient = null
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
                envVarName = method.varName,
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
