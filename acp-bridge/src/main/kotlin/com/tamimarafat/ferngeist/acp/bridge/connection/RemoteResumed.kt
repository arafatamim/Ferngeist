package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.protocol.Protocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The gateway's notice that it restored a session itself — the agent refused the
 * `session/load` as already loaded, so the gateway replays the session's buffered
 * updates and then answers the load. Those updates are ordinary `session/update`
 * notifications and must hydrate through the normal path; this notice only marks the
 * replay, so the app can log it and make sure buffering is active.
 */
@Serializable
internal data class RemoteResumed(
    val sessionId: String,
    val replayedFrames: Int = 0,
    @Suppress("ConstructorParameterNaming", "ktlint:standard:property-naming")
    @SerialName("_meta")
    override val _meta: JsonElement? = null,
) : AcpNotification

private val remoteResumedMethod =
    AcpMethod.AcpNotificationMethod("_ferngeist/resumed", serializer<RemoteResumed>())

/** Routes `_ferngeist/resumed` notices to [onResumed]. Register before [Protocol.start]. */
internal fun Protocol.onRemoteResumed(onResumed: suspend (sessionId: String, replayedFrames: Int) -> Unit) {
    setNotificationHandlerRaw(remoteResumedMethod, EmptyCoroutineContext) { notification ->
        val params = notification.params as? JsonObject ?: return@setNotificationHandlerRaw
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return@setNotificationHandlerRaw
        onResumed(sessionId, params["replayedFrames"]?.jsonPrimitive?.intOrNull ?: 0)
    }
}
