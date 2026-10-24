package com.tamimarafat.ferngeist.acp.bridge.connection

/** What an [AcpTransportClient] reports back to the [AcpConnection] that owns it. */
internal interface AcpTransportListener {
    fun updateConnectionState(state: AcpConnectionState)

    suspend fun emitManagerEvent(event: AcpManagerEvent)

    /** Runs after a background reconnect's handshake, before Connected is announced. */
    suspend fun onReconnected(result: AcpInitializeResult) {}

    suspend fun onRemoteTurnEnded(
        sessionId: String,
        stopReason: String,
    ) {}

    /** Runs when the gateway reports it restored a session itself; see [RemoteResumed]. */
    suspend fun onRemoteResumed(
        sessionId: String,
        replayedFrames: Int,
    ) {}
}
