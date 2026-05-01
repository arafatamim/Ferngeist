package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import java.net.URI

fun resolveGatewayWebSocketUrl(
    gatewaySource: GatewaySource,
    handoff: GatewayConnectResponse,
): String {
    val advertisedUrl = handoff.webSocketUrl.trim()
    val advertisedHost = runCatching { URI(advertisedUrl).host?.lowercase() }.getOrNull()
    if (advertisedHost != null && !isUnroutableGatewayHost(advertisedHost)) {
        return advertisedUrl
    }

    val socketScheme = when (gatewaySource.scheme.lowercase()) {
        "https", "wss" -> "wss"
        else -> "ws"
    }
    return "$socketScheme://${gatewaySource.host}${handoff.webSocketPath}"
}

fun isUnroutableGatewayHost(host: String): Boolean {
    return host == "0.0.0.0" || host == "127.0.0.1" || host == "localhost" || host == "::1"
}
