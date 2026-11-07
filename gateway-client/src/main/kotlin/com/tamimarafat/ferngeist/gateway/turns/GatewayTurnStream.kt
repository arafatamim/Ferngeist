package com.tamimarafat.ferngeist.gateway.turns

import com.tamimarafat.ferngeist.gateway.GatewayClock
import com.tamimarafat.ferngeist.gateway.GatewayHttpPipeline
import io.ktor.client.HttpClient
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

private const val SNAPSHOT_TYPE = "snapshot"
private const val CHANGES_TYPE = "changes"
private const val NORMAL_CLOSE = 1000
private const val GOING_AWAY_CLOSE = 1001

/** The gateway's turn status for every paired device, as the stream's snapshot frame carries it. */
@Serializable
data class GatewayTurnsSnapshot(
    /** The daemon start time; a new one means every status held for this gateway is stale. */
    val gatewayStartedAt: String,
    /** The single instant the gateway took this snapshot. */
    val observedAt: String,
    val turns: List<GatewayTurn> = emptyList(),
)

@Serializable
data class GatewayTurn(
    val agentId: String,
    val sessionId: String,
    /** The gateway session that ran the prompt; the same chat on another one is another entry. */
    val gatewaySessionId: String = "",
    val runtimeId: String? = null,
    /** `running`, `succeeded`, `failed`, `cancelled`, `unknown`, or a state a newer gateway adds. */
    val state: String,
    /** `permission` and/or `elicitation`; non-empty only while running. */
    val attention: List<String> = emptyList(),
    val startedAt: String? = null,
    /** When the entry's state last changed; attention changes do not move it. */
    val changedAt: String? = null,
    /** The agent's own stop reason, present only when the agent sent one. */
    val stopReason: String? = null,
)

/** One `/v1/turns/stream` frame: the opening snapshot, then the changed entries. */
sealed interface GatewayTurnStreamFrame {
    data class Snapshot(
        val snapshot: GatewayTurnsSnapshot,
    ) : GatewayTurnStreamFrame

    data class Changes(
        val observedAt: String,
        val upserts: List<GatewayTurn>,
        val removed: List<GatewayTurnKey>,
    ) : GatewayTurnStreamFrame
}

/** What a `removed` entry names: the stream's one key. */
@Serializable
data class GatewayTurnKey(
    val agentId: String,
    val gatewaySessionId: String,
    val sessionId: String,
)

/** The gateway closed the stream with a code the caller has to act on. */
class GatewayTurnStreamClosedException(
    val code: Int,
    val reason: String,
) : IOException("Gateway closed the turn stream with $code")

/**
 * The gateway's turn status feed for one paired device.
 *
 * [frames] is cold: collecting it opens `GET /v1/turns/stream`, emits until the gateway closes
 * it, and closes the socket when the collection is cancelled. A refused upgrade throws
 * `GatewayRequestException` (a 404 means the gateway predates the stream); a close the caller
 * must act on throws [GatewayTurnStreamClosedException].
 */
class GatewayTurnStream(
    webSocketClient: HttpClient,
    json: Json,
    clock: GatewayClock,
) {
    private val http = GatewayHttpPipeline(webSocketClient, json, clock)

    // A newer gateway may add members to a frame; the wire contract says to ignore them.
    private val frameJson = Json(json) { ignoreUnknownKeys = true }

    fun frames(
        scheme: String,
        host: String,
        credential: String,
    ): Flow<GatewayTurnStreamFrame> =
        channelFlow {
            http.webSocket(scheme, host, credential, "v1", "turns", "stream") { session ->
                for (frame in session.incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    decodeTurnStreamFrame(frameJson, text)?.let { send(it) }
                }
                val closed = session.closeReason.await() ?: return@webSocket
                val code = closed.code.toInt()
                if (code == NORMAL_CLOSE || code == GOING_AWAY_CLOSE) return@webSocket
                throw GatewayTurnStreamClosedException(code, closed.message)
            }
        }
}

@Serializable
private data class TurnStreamFrameDto(
    val type: String,
    val gatewayStartedAt: String? = null,
    val observedAt: String? = null,
    val turns: List<GatewayTurn>? = null,
    val upserts: List<GatewayTurn>? = null,
    val removed: List<GatewayTurnKey>? = null,
)

/** Decodes one text frame, or null for a `type` this client predates. */
internal fun decodeTurnStreamFrame(
    json: Json,
    text: String,
): GatewayTurnStreamFrame? = json.decodeFromString<TurnStreamFrameDto>(text).toFrame()

private fun TurnStreamFrameDto.toFrame(): GatewayTurnStreamFrame? {
    val observed = observedAt ?: return null
    return when (type) {
        SNAPSHOT_TYPE ->
            gatewayStartedAt?.let {
                GatewayTurnStreamFrame.Snapshot(GatewayTurnsSnapshot(it, observed, turns.orEmpty()))
            }
        CHANGES_TYPE -> GatewayTurnStreamFrame.Changes(observed, upserts.orEmpty(), removed.orEmpty())
        else -> null
    }
}
