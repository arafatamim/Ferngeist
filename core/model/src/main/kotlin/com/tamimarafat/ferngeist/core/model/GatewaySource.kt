package com.tamimarafat.ferngeist.core.model

import java.util.UUID

/**
 * A paired gateway. Gateway-backed launch targets reference this
 * record instead of storing pairing credentials inside each target row.
 */
data class GatewaySource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val scheme: String = "http",
    val host: String,
    val gatewayCredential: String,
    val gatewayCredentialExpiresAt: Long? = null,
    val gatewayRemoteMode: String? = null,
    // Gateway-owned identifier returned at pairing. Push payloads reference this as their
    // `serverId`; it's translated back to [id] for in-app deep-linking. Null for gateways
    // paired before the field existed (deep-links degrade to opening the app).
    val gatewayId: String? = null,
)
