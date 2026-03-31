package com.tamimarafat.ferngeist.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val scheme: String = "ws",
    val host: String,
    val token: String = "",
    val workingDirectory: String = "/",
    val preferredAuthMethodId: String? = null,
) {
    /**
     * `endpoint` remains the direct ACP target for manual sources.
     * Helper-backed sources will eventually resolve through a helper-specific
     * orchestration layer instead of connecting directly to this value.
     */
    val endpoint: String
        get() = "$scheme://$host"

}

@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val cwd: String? = null,
    val updatedAt: Long? = null,
)
