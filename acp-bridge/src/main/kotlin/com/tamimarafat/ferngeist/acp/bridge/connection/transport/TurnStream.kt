package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.tamimarafat.ferngeist.gateway.GatewayClock
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStream
import kotlinx.serialization.json.Json

/** The turn status socket, sharing the process's one CIO WebSocket client. */
fun gatewayTurnStream(
    json: Json,
    clock: GatewayClock,
): GatewayTurnStream = GatewayTurnStream(sharedWebSocketClient, json, clock)
