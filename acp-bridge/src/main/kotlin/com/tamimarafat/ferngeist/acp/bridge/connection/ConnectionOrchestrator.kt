package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

internal class ConnectionOrchestrator(
    private val connectivityObserver: ConnectivityObserver,
    private val gatewayRepository: com.tamimarafat.ferngeist.gateway.GatewayRepository?,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TRACE_TAG = "TSAcpLoad"
    }

    /** Exposes the raw transport state — Connected, Connecting, Disconnected, Failed. */
    private val _connectionState = MutableStateFlow<AcpConnectionState>(AcpConnectionState.Disconnected)
    val connectionState: StateFlow<AcpConnectionState> = _connectionState.asStateFlow()

    /**
     * Scoped management events: connected/disconnected, initialized, authenticated,
     * and transport errors. Consumed internally to update agent metadata; exposed
     * for callers that need to react to connection lifecycle changes.
     */
    private val _events = MutableSharedFlow<AcpManagerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AcpManagerEvent> = _events.asSharedFlow()

    /** Agent capabilities reported during initialization — prompt support, features, etc. */
    private val _agentCapabilities = MutableStateFlow<AgentCapabilities?>(null)
    val agentCapabilities: StateFlow<AgentCapabilities?> = _agentCapabilities.asStateFlow()

    /** Agent metadata (name, version) from initialization. */
    private val _agentInfo = MutableStateFlow<AgentInfo?>(null)
    val agentInfo: StateFlow<AgentInfo?> = _agentInfo.asStateFlow()

    /** Available authentication methods for this server. */
    private val authMethodsFlow = MutableStateFlow<List<AcpAuthMethodInfo>>(emptyList())

    /** Append-only diagnostic timeline for debugging and error display. */
    internal val diagnosticsStore = AcpDiagnosticsStore()
    val diagnostics: StateFlow<ConnectionDiagnostics> = diagnosticsStore.diagnostics

    internal val sdkClient: Client? get() = transportClient.sdkClient

    /**
     * Lightweight wrapper around the SDK's raw transport (TCP or WebSocket).
     * Owns connection lifecycle, reconnection, and diagnostics reporting.
     */
    private val transportClient = AcpTransportClient(
        connectivityObserver = connectivityObserver,
        gatewayRepository = gatewayRepository,
        scope = scope,
        diagnosticsStore = diagnosticsStore,
        updateConnectionState = { state -> _connectionState.value = state },
        emitManagerEvent = { event -> _events.emit(event) },
    )

    // Bridge between the one-shot event stream (emitted by AcpTransportClient)
    // and the StateFlow-based reactive state exposed to consumers. Without this
    // collector the metadata StateFlows would never update after connect/initialize.
    init {
        scope.launch {
            events.collect { event ->
                when (event) {
                    is AcpManagerEvent.Initialized -> {
                        _agentCapabilities.value = event.result.agentCapabilities
                        _agentInfo.value = event.result.agentInfo
                        authMethodsFlow.value = event.result.authMethods
                    }

                    is AcpManagerEvent.Disconnected -> {
                        _agentCapabilities.value = null
                        _agentInfo.value = null
                        authMethodsFlow.value = emptyList()
                    }

                    else -> Unit
                }
            }
        }
    }

    val isConnected: Boolean
        get() = _connectionState.value is AcpConnectionState.Connected

    suspend fun connect(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
    ): Boolean = transportClient.connect(config, resetState)

    suspend fun initialize(): AcpInitializeResult? {
        val result = transportClient.initialize()
        _agentCapabilities.value = result?.agentCapabilities
        _agentInfo.value = result?.agentInfo
        authMethodsFlow.value = result?.authMethods ?: emptyList()
        return result
    }

    fun currentConnectionConfig(): AcpConnectionConfig? = transportClient.currentConnectionConfig()

    suspend fun authenticate(methodId: String): AcpAuthenticateResult = transportClient.authenticate(methodId)

    fun disconnect(resetState: () -> Unit) {
        transportClient.disconnect(resetState)
    }

    suspend fun listSessions(cwd: String? = null): List<SessionSummary> {
        val client = transportClient.sdkClient ?: return emptyList()
        return runCatching {
            // client.listSessions returns a cold, finite Flow; .toList() collects
            // all items into memory — safe because the server returns a bounded set.
            @OptIn(UnstableApi::class)
            withTimeout(30_000L) {
                client.listSessions(cwd = cwd).toList().map { sessionInfo ->
                    SessionSummary(
                        id = sessionInfo.sessionId.value,
                        title = sessionInfo.title,
                        cwd = sessionInfo.cwd,
                        updatedAt = AcpSessionUpdateMapper.parseIsoOrMillis(sessionInfo.updatedAt),
                    )
                }
            }
        }.getOrElse {
            if (it is CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it
            toAuthRequiredException(it)?.let { error -> throw error }
            diagnosticsStore.appendError(
                "session/list",
                formatAcpErrorMessage(it, "Failed to list sessions"),
            )
            emptyList()
        }
    }

    internal suspend fun awaitConnectivityForReconnect() {
        transportClient.awaitConnectivityForReconnect()
    }

    internal fun resetAgentMetadata() {
        _agentCapabilities.value = null
        _agentInfo.value = null
        authMethodsFlow.value = emptyList()
    }

    internal fun logError(
        message: String,
        throwable: Throwable? = null,
    ) {
        runCatching { android.util.Log.e("AcpConnectionManager", message, throwable) }
    }

    internal fun trace(message: String) {
        runCatching { android.util.Log.d(TRACE_TAG, message) }
    }

    internal fun toAuthRequiredException(
        error: Throwable,
    ): AcpAuthenticationRequiredException? {
        if (!isAuthenticationRequiredError(error)) return null
        val challenge = currentAuthChallenge(
            message = formatAcpErrorMessage(error, "Authentication required"),
        ) ?: return null
        diagnosticsStore.appendError("authentication", challenge.message)
        return AcpAuthenticationRequiredException(challenge)
    }

    private fun currentAuthChallenge(message: String): AcpAuthChallenge? {
        val agent = _agentInfo.value ?: return null
        val methods = authMethodsFlow.value
        return AcpAuthChallenge(
            agentInfo = agent,
            authMethods = methods,
            message = message,
        )
    }

    private fun isAuthenticationRequiredError(error: Throwable): Boolean {
        val rpcError = error as? JsonRpcException
        if (rpcError?.code == JsonRpcErrorCode.AUTH_REQUIRED.code) return true
        val message = buildString {
            append(error.message.orEmpty())
            if (rpcError != null) {
                append(' ')
                append(rpcError.data?.toString().orEmpty())
            }
        }
        return message.contains("auth_required", ignoreCase = true) ||
            message.contains("authentication required", ignoreCase = true) ||
            message.contains("requires authentication", ignoreCase = true)
    }
}
