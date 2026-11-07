package com.tamimarafat.ferngeist.acp.bridge.connection.catchup

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.protocol.Protocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The gateway's notice that a prompt sent over an earlier connection has finished. That
 * turn's own reply answers a request this connection never made, so without the notice a
 * session reattached mid-turn keeps streaming forever.
 */
@Serializable
internal data class RemoteTurnEnded(
    val sessionId: String,
    /**
     * No reason means the gateway stated only that the turn ended: an unknown outcome, never
     * the success `end_turn` a missing field would otherwise imply.
     */
    val stopReason: String = "unknown",
    @Suppress("ConstructorParameterNaming", "ktlint:standard:property-naming")
    @SerialName("_meta")
    override val _meta: JsonElement? = null,
) : AcpNotification

private val remoteTurnEndedMethod =
    AcpMethod.AcpNotificationMethod("_ferngeist/turn_ended", serializer<RemoteTurnEnded>())

/**
 * The same end, replayed by the gateway ahead of a load/resume reply when the turn finished while
 * no client was attached; it adds the gateway's `durationMs`.
 */
private val remoteTurnResultMethod =
    AcpMethod.AcpNotificationMethod("_ferngeist/turn_result", serializer<RemoteTurnEnded>())

/**
 * The turn's length as the gateway measured it, from `_meta.ferngeist.durationMs` on a prompt
 * response. Null from an agent reached directly or from an older gateway.
 */
internal fun gatewayTurnDurationMs(meta: JsonElement?): Long? =
    ((meta as? JsonObject)?.get("ferngeist") as? JsonObject)
        ?.get("durationMs")
        ?.let { it as? JsonPrimitive }
        ?.longOrNull

/** Routes [RemoteTurnEnded] notices, live or replayed, to [onTurnEnded]. Register before [Protocol.start]. */
internal fun Protocol.onRemoteTurnEnded(
    onTurnEnded: suspend (sessionId: String, stopReason: String, durationMs: Long?) -> Unit,
) {
    for (method in listOf(remoteTurnEndedMethod, remoteTurnResultMethod)) {
        setNotificationHandlerRaw(method, EmptyCoroutineContext) { notification ->
            val params = notification.params as? JsonObject ?: return@setNotificationHandlerRaw
            val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return@setNotificationHandlerRaw
            val stopReason = params["stopReason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            onTurnEnded(sessionId, stopReason, params["durationMs"]?.jsonPrimitive?.longOrNull)
        }
    }
}
