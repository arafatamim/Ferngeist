package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoAttention
import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoConnectionState
import com.tamimarafat.ferngeist.acp.bridge.paseo.toPaseoConnection
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.PaseoSource
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A provider advertised by a paired Paseo daemon (public pairing-flow view). */
data class PaseoProviderInfo(
    val id: String,
    val name: String,
    val available: Boolean,
)

/**
 * The single public entry point to the Paseo backend. Owns one process-wide
 * [PaseoConnectionManager] (one active daemon connection at a time, mirroring the ACP
 * manager) and is shared by:
 * - the chat layer, as a [ChatSessionFacadeFactory] (dispatched to for Paseo targets), and
 * - the server/session-list screens, via the connection-state + session operations below.
 *
 * Sharing one manager means a session created from the session list is the same agent the
 * chat screen attaches to, and the list screens reflect the real Paseo connection state
 * (not the unrelated ACP transport).
 */
class PaseoBackend(
    private val launchableTargetRepository: LaunchableTargetRepository,
    appVersion: String,
    private val managerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ChatSessionFacadeFactory {
    private val connectionManager = PaseoConnectionManager(managerScope, appVersion)

    /** Registers a listener for `attention_required` events (drives local notifications). */
    fun setAttentionListener(listener: (PaseoAttention) -> Unit) {
        connectionManager.attentionSink = listener
    }

    // ---- Connection state for the list screens ----

    val connectionState: StateFlow<ChatConnectionState> =
        connectionManager.connectionState
            .map { mapState(it) }
            .stateIn(managerScope, SharingStarted.Eagerly, ChatConnectionState.Disconnected)

    val diagnostics: StateFlow<ChatConnectionDiagnostics> =
        connectionManager.connectionState
            .map { ChatConnectionDiagnostics(serverUrl = connectionManager.activeServerUrl) }
            .stateIn(managerScope, SharingStarted.Eagerly, ChatConnectionDiagnostics())

    val isConnected: Boolean get() = connectionManager.isConnected

    // ---- Operations ----

    suspend fun ensureConnected(source: PaseoSource): Boolean {
        if (connectionManager.isConnected) return true
        return connectionManager.connect(sourceId = source.id, connection = source.toPaseoConnection())
    }

    /** Pairs and lists providers (used by the pairing flow). */
    suspend fun discoverProviders(source: PaseoSource): List<PaseoProviderInfo> {
        if (!ensureConnected(source)) return emptyList()
        return connectionManager.discoverProviders(cwd = null)
            .map { PaseoProviderInfo(id = it.id, name = it.name, available = it.available) }
    }

    /** Lists the daemon's agents (sessions) for [provider] / [cwd]. */
    suspend fun listSessions(
        source: PaseoSource,
        provider: String,
        cwd: String?,
    ): List<SessionSummary> {
        if (!ensureConnected(source)) return emptyList()
        return connectionManager.listAgents(provider = provider, cwd = cwd)
    }

    /** Creates a new agent (session) on the daemon and returns its port. */
    suspend fun createSession(
        source: PaseoSource,
        provider: String,
        cwd: String,
        model: String?,
    ): SessionPort? {
        if (!ensureConnected(source)) return null
        return connectionManager.createSession(provider = provider, cwd = cwd, model = model)
    }

    fun disconnect() = connectionManager.disconnect()

    // ---- ChatSessionFacadeFactory (chat layer) ----

    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade =
        PaseoChatSessionFacade(
            scope = scope,
            connectionManager = connectionManager,
            launchableTargetRepository = launchableTargetRepository,
            serverId = serverId,
            initialSessionId = sessionId,
            cwd = cwd,
        )

    private fun mapState(state: PaseoConnectionState): ChatConnectionState =
        when (state) {
            PaseoConnectionState.Disconnected -> ChatConnectionState.Disconnected
            PaseoConnectionState.Connecting -> ChatConnectionState.Connecting
            PaseoConnectionState.Connected -> ChatConnectionState.Connected
            is PaseoConnectionState.Failed -> ChatConnectionState.Failed(state.error)
        }
}
