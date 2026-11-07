package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.Transport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Adds the ACP v1 boolean session-config capability to the outbound `initialize` request.
 *
 * ACP 0.33.0's generated [com.agentclientprotocol.model.ClientCapabilities] predates the
 * `session.configOptions.boolean` field, so the SDK cannot serialize it. Rewriting this one
 * request at the transport boundary keeps the rest of initialization and all SDK request
 * correlation on the normal path.
 */
internal class SessionConfigCapabilityTransport(
    private val delegate: Transport,
) : Transport by delegate {
    override fun send(frame: TransportFrame) {
        delegate.send(frame.withBooleanSessionConfigCapability())
    }
}

private fun TransportFrame.withBooleanSessionConfigCapability(): TransportFrame =
    when (this) {
        is TransportFrame.Single -> TransportFrame.Single(message.withBooleanSessionConfigCapability())
        is TransportFrame.Batch -> TransportFrame.Batch(entries.map { it.withBooleanSessionConfigCapability() })
        is TransportFrame.Malformed -> this
    }

private fun TransportFrame.Entry.withBooleanSessionConfigCapability(): TransportFrame.Entry =
    when (this) {
        is TransportFrame.Single -> TransportFrame.Single(message.withBooleanSessionConfigCapability())
        is TransportFrame.Malformed -> this
    }

private fun JsonRpcMessage.withBooleanSessionConfigCapability(): JsonRpcMessage {
    if (this !is JsonRpcRequest || method.name != INITIALIZE_METHOD) return this
    val params = params as? JsonObject ?: return this
    return copy(params = params.withBooleanSessionConfigCapability())
}

private fun JsonObject.withBooleanSessionConfigCapability(): JsonObject {
    val clientCapabilities = this["clientCapabilities"] as? JsonObject ?: JsonObject(emptyMap())
    val session = clientCapabilities["session"] as? JsonObject ?: JsonObject(emptyMap())
    val configOptions = session["configOptions"] as? JsonObject ?: JsonObject(emptyMap())

    val advertisedSession =
        buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put(
                "configOptions",
                buildJsonObject {
                    configOptions.forEach { (key, value) -> put(key, value) }
                    put("boolean", buildJsonObject {})
                },
            )
        }
    return buildJsonObject {
        forEach { (key, value) -> put(key, value) }
        put(
            "clientCapabilities",
            buildJsonObject {
                clientCapabilities.forEach { (key, value) -> put(key, value) }
                put("session", advertisedSession)
            },
        )
    }
}

private const val INITIALIZE_METHOD = "initialize"
