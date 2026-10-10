package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.rpc.ACPJson
import com.tamimarafat.ferngeist.core.model.SteerOutcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * Mid-turn steering over `_session/steering`, the extension claude-agent-acp and codex-acp
 * ship ahead of the spec. The standard is expected as `session/inject` (ACP RFD #1261,
 * advertised under agentCapabilities); moving to it means changing this file only.
 */

internal const val STEERING_METHOD = "_session/steering"

private const val STEERING_KEY = "steering"

/**
 * The adapters advertise steering in initialize's top-level `_meta`, not in the
 * capabilities. Folding it into the capabilities' `_meta` lets it travel wherever
 * capabilities already do, which is also where the standard will put it.
 */
@OptIn(UnstableApi::class)
internal fun AgentCapabilities.withSteeringMeta(initializeMeta: JsonElement?): AgentCapabilities {
    val steering = (initializeMeta as? JsonObject)?.get(STEERING_KEY) ?: return this
    val meta = (_meta as? JsonObject).orEmpty()
    return copy(_meta = JsonObject(meta + (STEERING_KEY to steering)))
}

internal fun AgentCapabilities.supportsSteering(): Boolean {
    val steering = (_meta as? JsonObject)?.get(STEERING_KEY) as? JsonObject
    return (steering?.get("supported") as? JsonPrimitive)?.booleanOrNull == true
}

internal fun steeringParams(
    sessionId: String,
    blocks: List<ContentBlock>,
): JsonObject =
    buildJsonObject {
        put("sessionId", sessionId)
        put("prompt", ACPJson.encodeToJsonElement(blocks))
        // An idle claude-agent-acp otherwise starts a turn of its own, which this client
        // neither owns nor can stop. codex-acp ignores the hint.
        putJsonObject("_meta") {
            putJsonObject(STEERING_KEY) { put("idleBehavior", "promptRequired") }
        }
    }

/** `promptRequired`, codex's `failed` and anything unknown all leave the prompt unsent. */
internal fun parseSteerOutcome(result: JsonElement): SteerOutcome =
    when (((result as? JsonObject)?.get("outcome") as? JsonPrimitive)?.contentOrNull) {
        "injected" -> SteerOutcome.Injected
        "startedNewTurn" -> SteerOutcome.StartedNewTurn
        else -> SteerOutcome.NotConsumed
    }
