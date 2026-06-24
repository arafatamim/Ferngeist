package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
 * Sessions are created via [createSession] (fresh) or [loadSession] (restored).
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
class AcpConnectionManager(
    connectivityObserver: ConnectivityObserver,
    private val gatewayRepository: com.tamimarafat.ferngeist.gateway.GatewayRepository?,
    scope: CoroutineScope,
) {
    private val orchestra = ConnectionOrchestrator(connectivityObserver, gatewayRepository, scope)
    private val permissionFlow = PermissionFlow()
    private val gateway = SessionGateway(
        orchestra = orchestra,
        permissionFlow = permissionFlow,
        bridgeFactory = { sessionId -> SessionBridge(sessionId, this) },
        scope = scope,
    )

    /** Exposes the raw transport state — Connected, Connecting, Disconnected, Failed. */
    val connectionState: StateFlow<AcpConnectionState> = orchestra.connectionState

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

    val isConnected: Boolean get() = orchestra.isConnected

    private fun resetConnectionState() {
        orchestra.resetAgentMetadata()
        gateway.clearAllSessions()
    }

    suspend fun connect(config: AcpConnectionConfig): Boolean =
        orchestra.connect(config, resetState = ::resetConnectionState)

    suspend fun initialize(): AcpInitializeResult? = orchestra.initialize()

    fun currentConnectionConfig(): AcpConnectionConfig? = orchestra.currentConnectionConfig()

    suspend fun authenticate(methodId: String): AcpAuthenticateResult = orchestra.authenticate(methodId)

    fun disconnect() {
        orchestra.disconnect(resetState = ::resetConnectionState)
    }

    suspend fun listSessions(cwd: String? = null): List<SessionSummary> = orchestra.listSessions(cwd)

    suspend fun createSession(cwd: String = "/"): SessionPort? = gateway.createSession(cwd)

    suspend fun loadSession(
        sessionId: String,
        cwd: String,
    ): SessionPort? = gateway.loadSession(sessionId, cwd)

    suspend fun sendSessionMessage(
        sessionId: String,
        content: String,
        images: List<Pair<String, String>> = emptyList(),
    ) {
        gateway.sendSessionMessage(sessionId, content, images)
    }

    suspend fun cancelSession(sessionId: String) {
        gateway.cancelSession(sessionId)
    }

    suspend fun setSessionMode(sessionId: String, modeId: String) {
        gateway.setSessionMode(sessionId, modeId)
    }

    suspend fun setSessionModel(sessionId: String, modelId: String) {
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
