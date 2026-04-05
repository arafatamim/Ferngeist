package com.tamimarafat.ferngeist.feature.serverlist.helper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DesktopHelperStatus(
    val name: String,
    val version: String,
    val remote: DesktopHelperRemoteStatus,
)

@Serializable
data class DesktopHelperRemoteStatus(
    val configured: Boolean,
    val mode: String? = null,
    val scope: String? = null,
    val healthy: Boolean = true,
    val warning: String? = null,
)

@Serializable
data class DesktopHelperPairCompleteRequest(
    @SerialName("challengeId") val challengeId: String,
    val code: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("proofPublicKey") val proofPublicKey: String? = null,
)

@Serializable
data class DesktopHelperPairCompleteResponse(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
    val token: String,
    @SerialName("expiresAt") val expiresAt: String,
)

data class DesktopHelperPairingResult(
    val deviceId: String,
    val deviceName: String,
    val helperCredential: String,
    val expiresAt: String,
)

@Serializable
data class DesktopHelperAgentsResponse(
    val agents: List<DesktopHelperAgent>,
)

@Serializable
data class DesktopHelperAgent(
    val id: String,
    val displayName: String,
    val detected: Boolean,
    val manifestValid: Boolean,
    val security: DesktopHelperAgentSecurity,
    val validationError: String? = null,
    val hint: String? = null,
    val running: Boolean = false,
    val runtimeId: String? = null,
    val runtimeStatus: String? = null,
)

@Serializable
data class DesktopHelperAgentSecurity(
    val allowsRemoteStart: Boolean,
)

@Serializable
data class DesktopHelperStartAgentResponse(
    val runtime: DesktopHelperRuntime,
)

@Serializable
data class DesktopHelperRuntime(
    val id: String,
    val status: String,
    val agentId: String,
)

@Serializable
data class DesktopHelperConnectResponse(
    val runtimeId: String,
    val scheme: String,
    val host: String,
    @SerialName("websocketUrl")
    val webSocketUrl: String,
    @SerialName("websocketPath")
    val webSocketPath: String,
    val bearerToken: String,
)

@Serializable
data class DesktopHelperRestartRequest(
    val env: Map<String, String>,
)

@Serializable
data class DesktopHelperRuntimeLogsResponse(
    val runtimeId: String,
    val logs: List<DesktopHelperLogEntry>,
)

@Serializable
data class DesktopHelperLogEntry(
    val timestamp: String,
    val stream: String,
    val message: String,
)

@Serializable
data class DesktopHelperPairStartResponse(
    @SerialName("challengeId") val challengeId: String,
    @SerialName("expiresAt") val expiresAt: String,
)

@Serializable
data class DesktopHelperPairStatusResponse(
    @SerialName("challengeId") val challengeId: String,
    @SerialName("expiresAt") val expiresAt: String,
    val state: String,
    @SerialName("completedDevice") val completedDevice: String? = null,
    @SerialName("completedDeviceId") val completedDeviceId: String? = null,
    @SerialName("completedDeviceExpiresAt") val completedDeviceExpiresAt: String? = null,
)
