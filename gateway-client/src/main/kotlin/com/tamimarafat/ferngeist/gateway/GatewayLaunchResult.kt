package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource

/**
 * The raw result of launching a gateway agent runtime: the (possibly
 * refreshed) gateway source and the connect handoff for the runtime the
 * gateway spawned.
 *
 * This deliberately contains no [AcpConnectionConfig] — that type lives in
 * acp-bridge, and this module must not depend upward. Callers compose their
 * own transport config from these pieces.
 */
data class GatewayLaunchResult(
    val gatewaySource: GatewaySource,
    val handoff: GatewayConnectResponse,
)
