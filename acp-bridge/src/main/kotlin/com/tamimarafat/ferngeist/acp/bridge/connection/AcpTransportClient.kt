package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.Implementation
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

    suspend fun initialize(): Boolean {
        val client = sdkClient ?: return false
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "initialize")
            val info = client.initialize(
                clientInfo = ClientInfo(
                    capabilities = ClientCapabilities(),
                    implementation = Implementation(name = "Ferngeist", version = "1.0.0"),
                )
            )

            val mapped = AgentInfo(
                name = info.implementation?.name?.takeIf { it.isNotBlank() } ?: "Agent",
                version = info.implementation?.version?.takeIf { it.isNotBlank() } ?: "unknown",
            )
            diagnosticsStore.recordInitialization(mapped)
            scope.launch { emitManagerEvent(AcpManagerEvent.Initialized(mapped)) }
            true
        }.getOrElse { error ->
            diagnosticsStore.appendError("initialize", error.message ?: "Initialization failed")
            scope.launch { emitManagerEvent(AcpManagerEvent.Error(error)) }
            false
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
            ) {
                config.authToken?.takeIf { it.isNotBlank() }?.let {
                    headers.append("Authorization", "Bearer $it")
                }
            }
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
            diagnosticsStore.appendError("connect", error.message ?: "Unknown connection failure")
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
}
