package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.Transport
import kotlinx.serialization.json.JsonObject

/**
 * Drops `agentCapabilities.auth.status` from inbound `initialize` results before the SDK decodes them.
 *
 * The v1 *draft* schema makes `auth.status` an object marker (`{}` = supports `auth/status`), and
 * claude-agent-acp >= 0.104.0 sends it that way. ACP Kotlin SDK 0.34.0 (and kotlin-sdk `master` as of
 * 2026-11-16) types it `Boolean?`, so the whole `initialize` fails to decode (GitHub #40). Ferngeist never
 * reads the field, so dropping it loses nothing.
 *
 * Delete this class, and its wrap in [AcpSocket], once the SDK types `status` as an object. That is
 * also the point to adopt `auth/status` if wanted: the field will then decode normally.
 */
internal class AuthStatusCapabilityTransport(
    private val delegate: Transport,
) : Transport by delegate {
    override fun onFrame(handler: (TransportFrame) -> Unit) {
        delegate.onFrame { handler(it.withoutAuthStatus()) }
    }
}

private fun TransportFrame.withoutAuthStatus(): TransportFrame =
    when (this) {
        is TransportFrame.Single -> TransportFrame.Single(message.withoutAuthStatus())
        is TransportFrame.Batch -> TransportFrame.Batch(entries.map { it.withoutAuthStatus() })
        is TransportFrame.Malformed -> this
    }

private fun TransportFrame.Entry.withoutAuthStatus(): TransportFrame.Entry =
    when (this) {
        is TransportFrame.Single -> TransportFrame.Single(message.withoutAuthStatus())
        is TransportFrame.Malformed -> this
    }

// Only an initialize result carries `agentCapabilities`, so no request-id tracking is needed.
private fun JsonRpcMessage.withoutAuthStatus(): JsonRpcMessage {
    val result = (this as? JsonRpcSuccessResponse)?.result as? JsonObject ?: return this
    val capabilities = result["agentCapabilities"] as? JsonObject ?: return this
    val auth = capabilities["auth"] as? JsonObject ?: return this
    val strippedCapabilities = JsonObject(capabilities + ("auth" to JsonObject(auth - "status")))
    return copy(result = JsonObject(result + ("agentCapabilities" to strippedCapabilities)))
}
