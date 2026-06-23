package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GatewayStatus(
    val name: String,
    val version: String,
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

@Serializable
data class GatewaySessionResumeResponse(
    @SerialName("attachToken") val attachToken: String,
)

@Serializable
data class GatewayPushTokenRequest(
    val token: String,
    // Always emit platform, even when it equals the default, so the gateway never
    // has to infer it (the shared Json has encodeDefaults = false).
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
)
