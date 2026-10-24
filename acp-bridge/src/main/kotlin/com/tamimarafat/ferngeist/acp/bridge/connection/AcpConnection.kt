package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point for ACP transport and session lifecycle.
 *
 * AcpConnection owns the raw transport ([AcpTransportClient]), the connect and
 * initialize handshake, authentication, background reconnect (plus the session
 * restore that follows it), diagnostics, and agent metadata. Session operations
 * live in [SessionGateway], exposed as [sessions]; callers drive those through it
 * directly instead of through one-line delegations here.
 *
 * ## Error handling
 * Errors are recorded in [diagnostics] and surfaced as [ConnectionDiagnostics].
 * Auth-required conditions are detected generically from error messages (not
 * vendor-specific codes) to maximise ACP server compatibility.
 */
@Suppress("TooManyFunctions") // the module's single connection entry point
class AcpConnection(
    connectivityObserver: ConnectivityObserver,
    // Mints a fresh attachToken for a gateway session; see [AcpTransportClient].
    resumeAttachToken: suspend (
        gateway: AcpConnectionConfig.Gateway,
        session: AcpConnectionConfig.ResumeInfo,
    ) -> String,
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

    /**
     * Session lifecycle, RPC dispatch, and permission/elicitation resolution. Public to
     * this module so callers use these operations directly; the chat layer sees only
     * the [com.tamimarafat.ferngeist.acp.bridge.session.SessionPort] the gateway returns.
     */
    internal val sessions =
        SessionGateway(
            connection = this,
            permissionFlow = PermissionFlow(),
            elicitationFlow = ElicitationFlow(),
            bridgeFactory = { sessionId -> SessionBridge(sessionId, this) },
            scope = scope,
        )

    /**
     * Lightweight wrapper around the SDK's raw transport (TCP or WebSocket).
     * Owns connection lifecycle, reconnection, and diagnostics reporting.
     */
    private val transportClient =
        AcpTransportClient(
            connectivityObserver = connectivityObserver,
            resumeAttachToken = resumeAttachToken,
            scope = scope,
            diagnosticsStore = diagnosticsStore,
            listener =
                object : AcpTransportListener {
                    override fun updateConnectionState(state: AcpConnectionState) {
                        _connectionState.value = state
                    }

                    override suspend fun emitManagerEvent(event: AcpManagerEvent) {
                        _events.emit(event)
                    }

                    // Runs after a background reconnect's handshake, before Connected is announced.
                    override suspend fun onReconnected(result: AcpInitializeResult) {
                        restoreSessions(result)
                    }

                    override suspend fun onRemoteTurnEnded(
                        sessionId: String,
                        stopReason: String,
                    ) {
                        sessions.endRemoteTurn(sessionId, stopReason)
                    }

                    override suspend fun onRemoteResumed(
                        sessionId: String,
                        replayedFrames: Int,
                    ) {
                        sessions.onRemoteResumed(sessionId, replayedFrames)
                    }
                },
        )

    /** True while a background reconnect loop is armed, including its backoff waits. */
    val reconnectPending: StateFlow<Boolean> = transportClient.reconnectPending

    // Sessions this connection held when its transport dropped, keyed to their cwd.
    // A fresh socket starts with an empty SDK session table, so until each is
    // attached again the SDK rejects the agent's traffic for it ("Session … not
    // found") — streamed output is lost and permission requests fail the turn. A
    // chat screen re-attaches its own session, but a pooled chat has no screen, so
    // the connection restores them itself; see [restoreSessions].
    private val sessionsToRestore = ConcurrentHashMap<String, String>()

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

    /** Records a timing summary in the diagnostics RPC log, readable on-device without adb. */
    internal fun recordTiming(
        label: String,
        summary: String,
    ) {
        diagnosticsStore.appendRpcEntry(RpcDirection.INBOUND_RESULT, label, summary = summary)
    }

    // A transport reset detaches sessions but keeps their bridges: the transcript a
    // bridge holds is the only copy for an agent that reattaches with `session/resume`
    // (no replay), and the hub's streaming read is bound to the bridge object.
    private fun resetConnectionState() {
        sessionsToRestore.putAll(sessions.registeredSessionCwds())
        resetAgentMetadata()
        sessions.detachAllSessions()
    }

    private fun releaseConnectionState() {
        resetAgentMetadata()
        sessions.clearAllSessions()
    }

    /**
     * Re-attaches the sessions held before a background reconnect. Runs before the
     * reconnect announces Connected, so a chat screen's own recovery then finds its
     * session registered instead of loading it a second time. A failure is left to
     * that recovery (and recorded in diagnostics by the attach itself).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun restoreSessions(result: AcpInitializeResult) {
        val rpc = result.agentCapabilities.sessionAttachRpc()
        val toRestore = sessionsToRestore.entries.associate { it.key to it.value }
        sessionsToRestore.clear()
        if (rpc == null) return
        for ((sessionId, cwd) in toRestore) {
            try {
                withTimeout(SESSION_RESTORE_TIMEOUT_MS) { sessions.attachSession(rpc, sessionId, cwd) }
            } catch (_: TimeoutCancellationException) {
                diagnosticsStore.appendError(rpc.rpc, "Restoring session $sessionId timed out")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Already recorded by the attach; the screen's recovery retries it.
            }
        }
    }

    suspend fun connect(config: AcpConnectionConfig): Boolean =
        transportClient.connect(config, resetState = ::resetConnectionState).also { sessionsToRestore.clear() }

    /**
     * Connects the transport and runs the ACP initialize handshake.
     * Callers dispatch to [kotlinx.coroutines.Dispatchers.IO] when needed (both calls are
     * network-bound).
     *
     * @return the initialize result, or null when connect or initialize failed.
     */
    suspend fun connectAndInitialize(config: AcpConnectionConfig): AcpInitializeResult? {
        if (!connect(config)) return null
        return initialize()
    }

    /**
     * Connects the transport and runs the ACP initialize handshake without
     * scheduling a background reconnect on failure. For short-lived listing
     * surfaces (e.g. the session-list browser transport) where perpetual
     * "Connecting..." after a failed handshake is worse than a single clear
     * error. Chat facades keep using [connect] so backgrounded sessions keep
     * streaming through transient drops.
     *
     * @return the initialize result, or null when connect or initialize failed.
     */
    suspend fun connectAndInitializeWithoutReconnect(config: AcpConnectionConfig): AcpInitializeResult? {
        val connected = transportClient.connectWithoutReconnect(config, resetState = ::resetConnectionState)
        sessionsToRestore.clear()
        if (!connected) return null
        return initialize()
    }

    suspend fun initialize(): AcpInitializeResult? {
        val result = transportClient.handshake.initialize()
        _agentCapabilities.value = result?.agentCapabilities
        _agentInfo.value = result?.agentInfo
        authMethodsFlow.value = result?.authMethods.orEmpty()
        return result
    }

    fun currentConnectionConfig(): AcpConnectionConfig? = transportClient.currentConnectionConfig

    suspend fun authenticate(methodId: String): AcpAuthenticateResult = transportClient.handshake.authenticate(methodId)

    fun disconnect() {
        transportClient.disconnect(resetState = ::releaseConnectionState)
        sessionsToRestore.clear()
    }

    /** Writes a ping so a socket whose peer vanished fails now; see [AcpTransportClient.probe]. */
    fun probeConnection() = transportClient.probe()

    /**
     * Hard release: cancels any reconnect loop and wipes sessions and the SDK
     * protocol via [disconnect]. The WebSocket client is process-wide, so there is
     * nothing further to free. Idempotent.
     */
    fun release() {
        disconnect()
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
        }.getOrElse { error -> throw listingFailure(error) }
    }

    /**
     * What a failed listing raises. A real cancellation — not our own 10s bound — is the caller
     * going away, so it passes through untouched. Everything else is raised as itself: an auth
     * challenge replaces the failure and is not also logged as a listing error, and any other
     * cause is recorded before it is re-raised, because the caller needs the failure rather than
     * an empty list that reads as "this agent has no sessions".
     */
    private fun listingFailure(error: Throwable): Throwable {
        if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) {
            return error
        }
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
        return auth ?: failure
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

    private fun resetAgentMetadata() {
        _agentCapabilities.value = null
        _agentInfo.value = null
        authMethodsFlow.value = emptyList()
    }

    internal fun logError(
        message: String,
        throwable: Throwable? = null,
    ) {
        runCatching { android.util.Log.e("AcpConnection", message, throwable) }
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

// Same deadline a chat screen gives its own session/load.
private const val SESSION_RESTORE_TIMEOUT_MS = 60_000L

/** Agent metadata from the ACP initialize handshake. */
data class AgentInfo(
    val name: String,
    val version: String,
)

/**
 * Events emitted by [AcpConnection.events] for connection lifecycle
 * observers.
 */
sealed interface AcpManagerEvent {
    data object Connected : AcpManagerEvent

    data object Disconnected : AcpManagerEvent

    /**
     * Gateway `resume` answered 400: the session no longer exists (the gateway
     * restarted, or its agent failed). The reconnect loop has stopped; the consumer
     * starts a fresh connect and attaches to the session that returns.
     */
    data object SessionResumeRejected : AcpManagerEvent

    data class Initialized(
        val result: AcpInitializeResult,
    ) : AcpManagerEvent

    data class Authenticated(
        val methodId: String,
    ) : AcpManagerEvent

    data class Error(
        val throwable: Throwable,
    ) : AcpManagerEvent
}

/**
 * Thrown when a session listing cannot run or could not complete, so the caller can
 * tell an unanswered listing apart from an empty one.
 */
class SessionListingUnavailableException(
    message: String,
) : IllegalStateException(message)
