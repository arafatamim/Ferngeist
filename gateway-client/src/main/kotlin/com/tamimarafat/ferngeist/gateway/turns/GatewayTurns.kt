package com.tamimarafat.ferngeist.gateway.turns

import com.tamimarafat.ferngeist.gateway.GatewayClock
import com.tamimarafat.ferngeist.gateway.GatewayHttpPipeline
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** One `/v1/turns` answer: the gateway's in-memory turn status for every paired device. */
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

class GatewayTurns
    @Inject
    constructor(
        httpClient: HttpClient,
        json: Json,
        clock: GatewayClock = GatewayClock(),
    ) {
        private val http = GatewayHttpPipeline(httpClient, json, clock)

        /** Served from the gateway's memory, never a listing; a gateway that predates it answers 404. */
        suspend fun fetchTurns(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): GatewayTurnsSnapshot = http.getJson(scheme, host, gatewayCredential, "v1", "turns")
    }
