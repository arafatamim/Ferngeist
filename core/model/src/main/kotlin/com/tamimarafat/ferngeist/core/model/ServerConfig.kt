package com.tamimarafat.ferngeist.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ServerSourceKind {
    MANUAL_ACP,
    DESKTOP_HELPER,
}

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceKind: ServerSourceKind = ServerSourceKind.MANUAL_ACP,
    val scheme: String = "ws",
    val host: String,
    val token: String = "",
    val workingDirectory: String = "/",
    val preferredAuthMethodId: String? = null,
    val helperCredential: String = "",
    val helperCredentialExpiresAt: Long? = null,
    val helperRemoteMode: String? = null,
    val helperSourceId: String? = null,
    val selectedAgentId: String? = null,
    val selectedAgentName: String? = null,
) {
    /**
     * `endpoint` remains the direct ACP target for manual sources.
     * Helper-backed sources will eventually resolve through a helper-specific
     * orchestration layer instead of connecting directly to this value.
     */
    val endpoint: String
        get() = "$scheme://$host"

    val isDesktopHelper: Boolean
        get() = sourceKind == ServerSourceKind.DESKTOP_HELPER

    /**
     * A paired desktop companion record stores helper credentials and metadata
     * but does not represent a directly launchable agent in the main list.
     */
    val isDesktopCompanion: Boolean
        get() = isDesktopHelper && selectedAgentId.isNullOrBlank()

    /**
     * A helper-backed agent entry is a launchable item derived from a paired
     * desktop companion and shown in the main server list alongside manual ACP.
     */
    val isDesktopHelperAgent: Boolean
        get() = isDesktopHelper && !selectedAgentId.isNullOrBlank()
}

@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val cwd: String? = null,
    val updatedAt: Long? = null,
)
