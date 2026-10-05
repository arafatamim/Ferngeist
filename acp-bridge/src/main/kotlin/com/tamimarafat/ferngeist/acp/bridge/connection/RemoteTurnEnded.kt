package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.protocol.Protocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The gateway's notice that a prompt sent over an earlier connection has finished. That
 * turn's own reply answers a request this connection never made, so without the notice a
 * session reattached mid-turn keeps streaming forever.
 */
@Serializable
internal data class RemoteTurnEnded(
    val sessionId: String,
    val stopReason: String = "end_turn",
    @Suppress("ConstructorParameterNaming", "ktlint:standard:property-naming")
    @SerialName("_meta")
    override val _meta: JsonElement? = null,
) : AcpNotification

private val remoteTurnEndedMethod =
    AcpMethod.AcpNotificationMethod("_ferngeist/turn_ended", RemoteTurnEnded.serializer())

/** Routes [RemoteTurnEnded] notices to [onTurnEnded]. Register before [Protocol.start]. */
internal fun Protocol.onRemoteTurnEnded(onTurnEnded: suspend (sessionId: String, stopReason: String) -> Unit) {
    setNotificationHandlerRaw(remoteTurnEndedMethod, EmptyCoroutineContext) { notification ->
        val params = notification.params as? JsonObject ?: return@setNotificationHandlerRaw
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return@setNotificationHandlerRaw
        onTurnEnded(sessionId, params["stopReason"]?.jsonPrimitive?.contentOrNull ?: "end_turn")
    }
}
