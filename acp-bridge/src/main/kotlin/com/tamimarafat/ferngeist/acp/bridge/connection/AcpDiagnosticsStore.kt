package com.tamimarafat.ferngeist.acp.bridge.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class AcpDiagnosticsStore {
    private val _diagnostics = MutableStateFlow(ConnectionDiagnostics())
    val diagnostics: StateFlow<ConnectionDiagnostics> = _diagnostics.asStateFlow()

    var agentInfo: AgentInfo? = null
        private set

    var supportsSessionCancel: Boolean? = null
        private set

    private val maxDiagnosticRpcEntries = 40
    private val maxDiagnosticErrorEntries = 20

    fun startConnect(serverUrl: String) {
        agentInfo = null
        supportsSessionCancel = null
        update { current ->
            current.copy(
                serverUrl = serverUrl,
                agentInfo = null,
                supportsSessionCancel = null,
            )
        }
    }

    fun recordInitialization(info: AgentInfo) {
        agentInfo = info
        update { current ->
            current.copy(
                agentInfo = info,
                supportsSessionCancel = supportsSessionCancel,
            )
        }
    }

    fun clearRuntimeState() {
        agentInfo = null
        supportsSessionCancel = null
    }

    fun markDisconnected() {
        clearRuntimeState()
        update { current ->
            current.copy(
                websocketState = WebSocketState.CLOSED,
                agentInfo = null,
                supportsSessionCancel = null,
            )
        }
    }

    fun setWebSocketState(state: WebSocketState) {
        update { current -> current.copy(websocketState = state) }
    }

    fun setSessionCancelSupport(isSupported: Boolean) {
        supportsSessionCancel = isSupported
        update { current -> current.copy(supportsSessionCancel = isSupported) }
    }

    fun appendRpcEntry(
        direction: RpcDirection,
        method: String,
        rpcId: String? = null,
        summary: String? = null,
    ) {
        val entry = RpcDiagnosticEntry(
            direction = direction,
            method = method,
            rpcId = rpcId,
            summary = summary,
        )
        update { current ->
            val next = (current.recentRpc + entry).takeLast(maxDiagnosticRpcEntries)
            current.copy(recentRpc = next)
        }
    }

    fun appendError(source: String, message: String) {
        val entry = DiagnosticErrorEntry(source = source, message = message)
        update { current ->
            val next = (current.recentErrors + entry).takeLast(maxDiagnosticErrorEntries)
            current.copy(recentErrors = next)
        }
    }

    private inline fun update(
        transform: (ConnectionDiagnostics) -> ConnectionDiagnostics,
    ) {
        _diagnostics.update { current ->
            transform(current).copy(lastUpdatedAtMs = System.currentTimeMillis())
        }
    }
}
