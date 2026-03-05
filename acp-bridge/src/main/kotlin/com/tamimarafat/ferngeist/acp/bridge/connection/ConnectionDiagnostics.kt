package com.tamimarafat.ferngeist.acp.bridge.connection

data class ConnectionDiagnostics(
    val websocketState: WebSocketState = WebSocketState.CLOSED,
    val serverUrl: String? = null,
    val agentInfo: AgentInfo? = null,
    val supportsSessionCancel: Boolean? = null,
    val lastTotalTokens: Int? = null,
    val lastContextWindowTokens: Int? = null,
    val lastCostAmount: Double? = null,
    val lastCostCurrency: String? = null,
    val pendingRequestCount: Int = 0,
    val recentRpc: List<RpcDiagnosticEntry> = emptyList(),
    val recentErrors: List<DiagnosticErrorEntry> = emptyList(),
    val lastUpdatedAtMs: Long = System.currentTimeMillis(),
)

data class RpcDiagnosticEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val direction: RpcDirection,
    val method: String,
    val rpcId: String? = null,
    val summary: String? = null,
)

data class DiagnosticErrorEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val source: String,
    val message: String,
)

enum class RpcDirection {
    OutboundRequest,
    InboundResult,
    InboundError,
    InboundNotification,
}

enum class WebSocketState {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED,
    FAILED,
}
