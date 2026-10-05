package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class ConnectionOrchestrator(
    private val connectivityObserver: ConnectivityObserver,
    private val gatewayRepository: com.tamimarafat.ferngeist.gateway.GatewayRepository?,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val LIST_SESSIONS_TIMEOUT_MS = 30_000L
        private const val DELETE_SESSION_TIMEOUT_MS = 10_000L
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

    /** Runs after a background reconnect's handshake, before Connected is announced. */
    var onReconnected: suspend (AcpInitializeResult) -> Unit = {}

    /** Runs when the gateway reports a turn started over an earlier connection has ended. */
    var onRemoteTurnEnded: suspend (sessionId: String, stopReason: String) -> Unit = { _, _ -> }

    /**
     * Lightweight wrapper around the SDK's raw transport (TCP or WebSocket).
     * Owns connection lifecycle, reconnection, and diagnostics reporting.
     */
    private val transportClient =
        AcpTransportClient(
            connectivityObserver = connectivityObserver,
            gatewayRepository = gatewayRepository,
            scope = scope,
            diagnosticsStore = diagnosticsStore,
            updateConnectionState = { state -> _connectionState.value = state },
            emitManagerEvent = { event -> _events.emit(event) },
            onReconnected = { result -> onReconnected(result) },
            onRemoteTurnEnded = { sessionId, stopReason -> onRemoteTurnEnded(sessionId, stopReason) },
        )

    val reconnectPending: StateFlow<Boolean> = transportClient.reconnectPending

    fun probe() = transportClient.probe()

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

    suspend fun connectWithoutReconnect(
        config: AcpConnectionConfig,
        resetState: () -> Unit,
    ): Boolean = transportClient.connectWithoutReconnect(config, resetState)

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

    /** Releases heavyweight resources (shared [HttpClient]); idempotent. */
    fun close() {
        transportClient.close()
    }

    /**
     * Lists the agent's sessions for [cwd].
     *
     * A failure is *thrown*, never folded into an empty list. "The agent could not
     * answer" and "the agent has no sessions" are different facts, and the caller
     * persists what it receives ([ChatConnectionHub] replaces the stored rows): an
     * empty list on failure deleted the user's cached sessions and painted an empty
     * directory. The 10s bound is converted into a failure for the same reason — as a
     * [kotlinx.coroutines.TimeoutCancellationException] it reads as a cancellation and
     * disappears into the caller's coroutine instead of reaching the retry path.
     *
     * @throws SessionListingUnavailableException when no connected client can list.
     * @throws AcpAuthenticationRequiredException when the agent demands authentication.
     */
    suspend fun listSessions(cwd: String? = null): List<SessionSummary> {
        val client =
            transportClient.sdkClient
                ?: throw SessionListingUnavailableException(
                    "The agent is not connected, so its sessions could not be listed.",
                )
        return runCatching {
            // client.listSessions returns a cold, finite Flow; .toList() collects
            // all items into memory — safe because the server returns a bounded set.
            @OptIn(UnstableApi::class)
            withTimeout(LIST_SESSIONS_TIMEOUT_MS) {
                client.listSessions(cwd = cwd).toList().map { sessionInfo ->
                    SessionSummary(
                        id = sessionInfo.sessionId.value,
                        title = sessionInfo.title,
                        cwd = sessionInfo.cwd,
                        updatedAt = AcpSessionUpdateMapper.parseIsoOrMillis(sessionInfo.updatedAt),
                    )
                }
            }
        }.getOrElse { error ->
            // A real cancellation — not our own 10s bound — is the caller going away, so it
            // passes through untouched. Everything else is raised as itself: an auth challenge
            // replaces the failure and is not also logged as a listing error, and any other
            // cause is recorded before it is re-raised, because the caller needs the failure
            // rather than an empty list that reads as "this agent has no sessions".
            val realCancellation =
                error is CancellationException &&
                    error !is kotlinx.coroutines.TimeoutCancellationException
            val toRaise =
                if (realCancellation) {
                    error
                } else {
                    val failure =
                        if (error is kotlinx.coroutines.TimeoutCancellationException) {
                            SessionListingUnavailableException(
                                "Timed out waiting for the agent to list its sessions " +
                                    "(${LIST_SESSIONS_TIMEOUT_MS / 1000}s). Check the connection and retry.",
                            )
                        } else {
                            error
                        }
                    val auth = toAuthRequiredException(failure)
                    if (auth == null) {
                        diagnosticsStore.appendError(
                            "session/list",
                            formatAcpErrorMessage(failure, "Failed to list sessions"),
                        )
                    }
                    auth ?: failure
                }
            throw toRaise
        }
    }

    /**
     * Sends `session/delete` for [sessionId]. Returns false when no client is
     * connected or the agent rejected the call; timeouts degrade the same way,
     * while a plain cancellation propagates like [listSessions].
     */
    @OptIn(UnstableApi::class)
    suspend fun deleteSession(sessionId: String): Boolean {
        val client = transportClient.sdkClient ?: return false
        return runCatching {
            withTimeout(DELETE_SESSION_TIMEOUT_MS) {
                client.deleteSession(SessionId(sessionId))
            }
            true
        }.getOrElse {
            if (it is CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it
            diagnosticsStore.appendError(
                "session/delete",
                formatAcpErrorMessage(it, "Failed to delete session"),
            )
            false
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

    internal fun toAuthRequiredException(error: Throwable): AcpAuthenticationRequiredException? {
        if (!isAuthenticationRequiredError(error)) return null
        val challenge =
            currentAuthChallenge(
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

    /**
     * True when the failure is the agent asking *this client* to authenticate.
     *
     * The protocol's own signal is [JsonRpcErrorCode.AUTH_REQUIRED]; the wording
     * fallback exists for agents that report it as a plain error. It reads
     * [Throwable.message] only: the JSON-RPC `data` field is agent-authored payload
     * (a provider failure such as "authentication required for provider X" travels
     * there), and matching it presented an agent-side auth problem as this client's
     * own ACP auth flow — a remedy that cannot fix it, while killing background
     * recovery.
     */
    private fun isAuthenticationRequiredError(error: Throwable): Boolean {
        val rpcError = error as? JsonRpcException
        if (rpcError?.code == JsonRpcErrorCode.AUTH_REQUIRED.code) return true
        val message = error.message.orEmpty()
        return message.contains("auth_required", ignoreCase = true) ||
            message.contains("authentication required", ignoreCase = true) ||
            message.contains("requires authentication", ignoreCase = true)
    }
}

/**
 * Thrown when a session listing cannot run or could not complete, so the caller can
 * tell an unanswered listing apart from an empty one.
 */
class SessionListingUnavailableException(
    message: String,
) : IllegalStateException(message)
