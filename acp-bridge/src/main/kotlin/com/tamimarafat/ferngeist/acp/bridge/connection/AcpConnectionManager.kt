package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Central orchestrator for ACP transport and session lifecycle.
 *
 * AcpConnectionManager is the single entry point for connecting, initializing,
 * authenticating, and creating/loading sessions. It delegates to:
 * - [ConnectionOrchestrator] (transport, auth, agent metadata, diagnostics)
 * - [SessionGateway] (session lifecycle, RPC dispatch, permission resolution)
 * - [PermissionFlow] (pending permission tracking)
 *
 * ## Session lifecycle
 * Sessions are created via [createSession] (fresh) or [attachSession] (restored).
 * Both return a [SessionPort] — the chat layer never sees the concrete
 * [SessionBridge]. Internally, the manager delegates to [SessionGateway] which
 * stores [SessionBridge] references and calls its internal methods
 * ([SessionBridge.emitEvent], [SessionBridge.beginHydration], etc.).
 *
 * ## Error handling
 * Errors are recorded in [diagnostics] and surfaced as
 * [ConnectionDiagnostics]. Auth-required conditions are detected generically
 * from error messages (not vendor-specific codes) to maximise ACP server
 * compatibility.
 */
@Suppress("TooManyFunctions") // the module's single entry point; each member is a one-line delegation
class AcpConnectionManager(
    connectivityObserver: ConnectivityObserver,
    private val gatewayRepository: com.tamimarafat.ferngeist.gateway.GatewayRepository?,
    scope: CoroutineScope,
) {
    private val orchestra = ConnectionOrchestrator(connectivityObserver, gatewayRepository, scope)
    private val permissionFlow = PermissionFlow()
    private val gateway =
        SessionGateway(
            orchestra = orchestra,
            permissionFlow = permissionFlow,
            bridgeFactory = { sessionId -> SessionBridge(sessionId, this) },
            scope = scope,
        )

    /** Exposes the raw transport state — Connected, Connecting, Disconnected, Failed. */
    val connectionState: StateFlow<AcpConnectionState> = orchestra.connectionState

    /** True while a background reconnect loop is armed, including its backoff waits. */
    val reconnectPending: StateFlow<Boolean> = orchestra.reconnectPending

    // Sessions this connection held when its transport dropped, keyed to their cwd.
    // A fresh socket starts with an empty SDK session table, so until each is
    // attached again the SDK rejects the agent's traffic for it ("Session … not
    // found") — streamed output is lost and permission requests fail the turn. A
    // chat screen re-attaches its own session, but a pooled chat has no screen, so
    // the connection restores them itself; see [restoreSessions].
    private val sessionsToRestore = ConcurrentHashMap<String, String>()

    init {
        orchestra.onReconnected = ::restoreSessions
    }

    /**
     * Scoped management events: connected/disconnected, initialized, authenticated,
     * and transport errors.
     */
    val events: SharedFlow<AcpManagerEvent> = orchestra.events

    /** Agent capabilities reported during initialization. */
    val agentCapabilities: StateFlow<AgentCapabilities?> = orchestra.agentCapabilities

    /** Agent metadata (name, version) from initialization. */
    val agentInfo: StateFlow<AgentInfo?> = orchestra.agentInfo

    /** Append-only diagnostic timeline for debugging and error display. */
    val diagnostics: StateFlow<ConnectionDiagnostics> = orchestra.diagnostics

    /** Records a timing summary in the diagnostics RPC log, readable on-device without adb. */
    internal fun recordTiming(
        label: String,
        summary: String,
    ) {
        orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.InboundResult, label, summary = summary)
    }

    val isConnected: Boolean get() = orchestra.isConnected

    // A transport reset detaches sessions but keeps their bridges: the transcript a
    // bridge holds is the only copy for an agent that reattaches with `session/resume`
    // (no replay), and the hub's streaming read is bound to the bridge object.
    private fun resetConnectionState() {
        sessionsToRestore.putAll(gateway.registeredSessionCwds())
        orchestra.resetAgentMetadata()
        gateway.detachAllSessions()
    }

    private fun releaseConnectionState() {
        orchestra.resetAgentMetadata()
        gateway.clearAllSessions()
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
        val sessions = sessionsToRestore.toMap()
        sessionsToRestore.clear()
        if (rpc == null) return
        for ((sessionId, cwd) in sessions) {
            try {
                withTimeout(SESSION_RESTORE_TIMEOUT_MS) { gateway.attachSession(rpc, sessionId, cwd) }
            } catch (_: TimeoutCancellationException) {
                orchestra.diagnosticsStore.appendError(rpc.rpc, "Restoring session $sessionId timed out")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Already recorded by the attach; the screen's recovery retries it.
            }
        }
    }

    suspend fun connect(config: AcpConnectionConfig): Boolean =
        orchestra.connect(config, resetState = ::resetConnectionState).also { sessionsToRestore.clear() }

    /**
     * Connects the transport and runs the ACP initialize handshake.
     * Callers dispatch to [Dispatchers.IO] when needed (both calls are
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
        val connected = orchestra.connectWithoutReconnect(config, resetState = ::resetConnectionState)
        sessionsToRestore.clear()
        if (!connected) return null
        return initialize()
    }

    suspend fun initialize(): AcpInitializeResult? = orchestra.initialize()

    fun currentConnectionConfig(): AcpConnectionConfig? = orchestra.currentConnectionConfig()

    suspend fun authenticate(methodId: String): AcpAuthenticateResult = orchestra.authenticate(methodId)

    fun disconnect() {
        orchestra.disconnect(resetState = ::releaseConnectionState)
        sessionsToRestore.clear()
    }

    /** Writes a ping so a socket whose peer vanished fails now; see [AcpTransportClient.probe]. */
    fun probeConnection() = orchestra.probe()

    /**
     * Hard release: cancels any reconnect loop, wipes sessions and the SDK
     * protocol via [disconnect], then frees the shared HttpClient via [close].
     * The disconnect-then-close ordering is load-bearing (close alone would let
     * an armed reconnect loop resurrect the transport), so it lives here rather
     * than with callers. Idempotent.
     */
    fun release() {
        disconnect()
        close()
    }

    /**
     * Tears down the transport and releases heavyweight resources (the shared
     * CIO [io.ktor.client.HttpClient]). Called when the owning scope completes;
     * [disconnect] alone only closes the SDK protocol and keeps the client for
     * reconnect reuse. Idempotent.
     */
    fun close() {
        orchestra.close()
    }

    suspend fun listSessions(cwd: String? = null): List<SessionSummary> = orchestra.listSessions(cwd)

    suspend fun createSession(cwd: String = ""): SessionPort? = gateway.createSession(cwd)

    /**
     * Attaches to an existing session through the RPC the agent advertises:
     * `session/load` (history replay) or `session/resume` (reattach only, no
     * transcript — the conversation continues from the agent's context). Callers
     * pick the RPC with [sessionAttachRpc].
     */
    suspend fun attachSession(
        rpc: SessionAttachRpc,
        sessionId: String,
        cwd: String,
    ): SessionPort? = gateway.attachSession(rpc, sessionId, cwd)

    suspend fun sendSessionMessage(
        sessionId: String,
        content: String,
        images: List<ChatImageData> = emptyList(),
        files: List<ChatFileData> = emptyList(),
    ) {
        gateway.sendSessionMessage(sessionId, content, images, files)
    }

    suspend fun cancelSession(sessionId: String) {
        gateway.cancelSession(sessionId)
    }

    /** Sends `session/delete`; false when there is no client or the agent rejected it. */
    suspend fun deleteSession(sessionId: String): Boolean = orchestra.deleteSession(sessionId)

    suspend fun setSessionMode(
        sessionId: String,
        modeId: String,
    ) {
        gateway.setSessionMode(sessionId, modeId)
    }

    suspend fun setSessionModel(
        sessionId: String,
        modelId: String,
    ) {
        gateway.setSessionModel(sessionId, modelId)
    }

    suspend fun setSessionConfigOption(
        sessionId: String,
        optionId: String,
        value: SessionConfigValue,
    ) {
        gateway.setSessionConfigOption(sessionId, optionId, value)
    }

    suspend fun respondPermissionSelected(
        sessionId: String,
        toolCallId: String,
        optionId: String,
    ) {
        gateway.respondPermissionSelected(sessionId, toolCallId, optionId)
    }

    suspend fun respondPermissionCancelled(
        sessionId: String,
        toolCallId: String,
    ) {
        gateway.respondPermissionCancelled(sessionId, toolCallId)
    }

    fun getSession(sessionId: String): SessionPort? = gateway.getSession(sessionId)

    fun removeSession(sessionId: String) {
        gateway.removeSession(sessionId)
    }

    internal suspend fun awaitConnectivityForReconnect() {
        orchestra.awaitConnectivityForReconnect()
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
 * Events emitted by [AcpConnectionManager.events] for connection lifecycle
 * observers.
 */
sealed interface AcpManagerEvent {
    data object Connected : AcpManagerEvent

    data object Disconnected : AcpManagerEvent

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
