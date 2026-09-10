package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GatewayStatus(
    val name: String,
    val version: String,
    @SerialName("protocolVersion")
    val protocolVersion: String? = null,
    val remote: GatewayRemoteStatus,
)

@Serializable
data class GatewayRemoteStatus(
    val configured: Boolean,
    val mode: String? = null,
    val scope: String? = null,
    val healthy: Boolean = true,
    val warning: String? = null,
)

@Serializable
data class GatewayPairCompleteRequest(
    @SerialName("challengeId") val challengeId: String,
    val code: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("proofPublicKey") val proofPublicKey: String? = null,
)

@Serializable
data class GatewayPairCompleteResponse(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
    val token: String,
    @SerialName("expiresAt") val expiresAt: String,
    // Stable, gateway-owned identifier for this gateway. Push payloads reference it as
    // their `serverId`; the client maps it back to the local GatewaySource.id to deep-link.
    // Nullable for back-compat with gateways that predate the field.
    @SerialName("gatewayId") val gatewayId: String? = null,
)

data class GatewayPairingResult(
    val deviceId: String,
    val deviceName: String,
    val gatewayCredential: String,
    val expiresAt: String,
    val gatewayId: String? = null,
)

@Serializable
data class GatewayAgentsResponse(
    val agents: List<GatewayAgent>,
)

@Serializable
data class GatewayAgent(
    val id: String,
    val displayName: String,
    val detected: Boolean,
    val manifestValid: Boolean,
    val security: GatewayAgentSecurity,
    val validationError: String? = null,
    val hint: String? = null,
    val running: Boolean = false,
    val runtimeId: String? = null,
    val runtimeStatus: String? = null,
)

@Serializable
data class GatewayAgentSecurity(
    val allowsRemoteStart: Boolean,
)

@Serializable
data class GatewayStartAgentResponse(
    val runtime: GatewayRuntime,
)

@Serializable
data class GatewayRuntime(
    val id: String,
    val status: String,
    val agentId: String,
)

@Serializable
data class GatewayConnectResponse(
    val runtimeId: String,
    val scheme: String,
    val host: String,
    @SerialName("websocketUrl")
    val webSocketUrl: String,
    @SerialName("websocketPath")
    val webSocketPath: String,
    val bearerToken: String,
    @SerialName("tokenExpiresAt")
    val tokenExpiresAt: String? = null,
    @SerialName("sessionId")
    val sessionId: String? = null,
    @SerialName("attachToken")
    val attachToken: String? = null,
)

@Serializable
data class GatewayRestartRequest(
    val env: Map<String, String>,
)

@Serializable
data class GatewayRuntimeLogsResponse(
    val runtimeId: String,
    val logs: List<GatewayLogEntry>,
)

@Serializable
data class GatewayLogEntry(
    val timestamp: String,
    val stream: String,
    val message: String,
)

@Serializable
data class GatewayPairStartResponse(
    @SerialName("challengeId") val challengeId: String,
    @SerialName("expiresAt") val expiresAt: String,
)

@Serializable
data class GatewayPairStatusResponse(
    @SerialName("challengeId") val challengeId: String,
    @SerialName("expiresAt") val expiresAt: String,
    val state: String,
    @SerialName("completedDevice") val completedDevice: String? = null,
    @SerialName("completedDeviceId") val completedDeviceId: String? = null,
    @SerialName("completedDeviceExpiresAt") val completedDeviceExpiresAt: String? = null,
)

@Serializable
data class GatewayConnectRequest(
    @SerialName("sessionMode") val sessionMode: String? = null,
)

/**
 * Body of `POST /v1/agents/{id}/start`.
 *
 * `new = true` makes the gateway spawn a distinct runtime instead of reusing
 * an existing one for the agent. That is the only lever that isolates a second
 * concurrent chat: the connect endpoint ignores this flag, because a runtime
 * reuses the one session it leases.
 */
@Serializable
data class GatewayStartRequest(
    @SerialName("new") val new: Boolean,
)

@Serializable
data class GatewaySessionResumeResponse(
    @SerialName("attachToken") val attachToken: String,
)

@Serializable
data class GatewayPushTokenRequest(
    val token: String,
    // Always emit platform, even when it equals the default, so the gateway never
    // has to infer it (connect request bodies force encodeDefaults = false locally).
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val platform: String = "android",
)

@Serializable
data class GatewaySessionListResponse(
    val sessions: List<GatewaySessionSummary>,
)

@Serializable
data class GatewaySessionSummary(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("runtimeId") val runtimeId: String,
    @SerialName("agentId") val agentId: String,
    val status: String,
    @SerialName("createdAt") val createdAt: String? = null,
) {
    /**
     * Whether the gateway will still resume this session.
     *
     * Mirrors the gateway's own check (`RuntimeSession.Resume` /
     * `FindReconnectableByRuntime`): only an `active` or `disconnected` session
     * holds a live runtime lease. Anything else (`failed`, `closing`) has a dead
     * runtime behind it, so attaching to its `runtimeId` fails on connect.
     */
    val isResumable: Boolean
        get() = status == STATUS_ACTIVE || status == STATUS_DISCONNECTED

    private companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_DISCONNECTED = "disconnected"
    }
}

/**
 * Text file read response from `GET /v1/runtimes/{id}/files`. The gateway returns the ACP
 * SDK's [com.agentclientprotocol.model.EmbeddedResourceResource.TextResourceContents] shape
 * directly; the gateway's `size`/`truncated` extensions are carried alongside.
 */
sealed interface GatewayFileRead {
    data class Text(
        val contents: com.agentclientprotocol.model.EmbeddedResourceResource.TextResourceContents,
        val size: Int,
        val truncated: Boolean = false,
    ) : GatewayFileRead

    data class Binary(
        val contents: com.agentclientprotocol.model.EmbeddedResourceResource.BlobResourceContents,
        val size: Int,
        val truncated: Boolean = false,
    ) : GatewayFileRead
}

@Serializable
data class GatewayChangedFile(
    val path: String,
    val status: String,
    val added: Int = 0,
    val removed: Int = 0,
    val binary: Boolean = false,
    /**
     * True when the entry is a directory rather than a file: a submodule
     * (gitlink) or a collapsed untracked directory. The gateway computes this
     * from `git status --porcelain=v2` (submodule `S` worktree status / trailing
     * slash) because the client cannot distinguish a submodule from a file
     * from the path alone. Directory entries are not diffable — tapping one
     * must not call the diff endpoint (which 422s on a gitlink).
     */
    val isDir: Boolean = false,
)

@Serializable
data class GatewayGitStatus(
    val branch: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val changed: List<GatewayChangedFile> = emptyList(),
)

/**
 * The gateway-to-client protocol version this client is built against. The gateway
 * reports its own protocol version in [GatewayStatus.protocolVersion]; a mismatch
 * means the two sides may not agree on the API surface (endpoints, payload shapes),
 * so the client surfaces a clear error instead of failing opaquely on a 404/422.
 */
object GatewayProtocol {
    /** The current protocol version (see gateway `internal/api/server.go`). */
    const val CURRENT = "v1"

    /**
     * All gateway protocol versions this client can talk to. The current version is
     * [CURRENT]; older stable versions remain supported so a gateway that has not been
     * updated yet keeps working (the client may simply not use newer endpoints).
     */
    val SUPPORTED: Set<String> = setOf(CURRENT, "v1alpha1")
}

/** Thrown when a gateway's reported protocol version is incompatible with this client. */
class GatewayProtocolMismatchException(
    val gatewayVersion: String?,
    val supportedVersions: Set<String> = GatewayProtocol.SUPPORTED,
) : IllegalStateException(
        "This gateway uses protocol version \"$gatewayVersion\", but this app supports " +
            supportedVersions.joinToString(", ") { "\"$it\"" } +
            ". Update the gateway daemon to continue.",
    )

/**
 * Validates a gateway status against the supported protocol versions, throwing
 * [GatewayProtocolMismatchException] on mismatch. A gateway that omits the field
 * (older build) is treated as a mismatch rather than silently assumed compatible.
 */
fun GatewayStatus.requireSupportedProtocol() {
    if (protocolVersion !in GatewayProtocol.SUPPORTED) {
        throw GatewayProtocolMismatchException(protocolVersion)
    }
}
