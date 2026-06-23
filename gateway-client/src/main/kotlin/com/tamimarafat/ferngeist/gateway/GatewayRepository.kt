package com.tamimarafat.ferngeist.gateway

interface GatewayRepository {
    suspend fun fetchStatus(
        scheme: String,
        host: String,
    ): GatewayStatus

    suspend fun startPairing(
        scheme: String,
        host: String,
    ): GatewayPairStartResponse

    suspend fun getPairingStatus(
        scheme: String,
        host: String,
        challengeId: String,
    ): GatewayPairStatusResponse

    suspend fun fetchAgents(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): List<GatewayAgent>

    suspend fun startAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
    ): GatewayRuntime

    suspend fun connectRuntime(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        sessionMode: String? = null,
    ): GatewayConnectResponse

    suspend fun restartRuntime(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        envVars: Map<String, String>,
    ): GatewayConnectResponse

    suspend fun fetchRuntimeLogs(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
    ): List<GatewayLogEntry>

    suspend fun completePairing(
        scheme: String,
        host: String,
        challengeId: String,
        code: String,
        deviceName: String,
    ): GatewayPairingResult

    suspend fun refreshCredential(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): GatewayPairingResult

    suspend fun resumeSession(
        scheme: String,
        host: String,
        gatewayCredential: String,
        sessionId: String,
    ): GatewaySessionResumeResponse

    suspend fun listGatewaySessions(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): List<GatewaySessionSummary>

    suspend fun closeSession(
        scheme: String,
        host: String,
        gatewayCredential: String,
        sessionId: String,
    )

    /**
     * Registers (or refreshes) this device's FCM push token with the gateway so it
     * can deliver background notifications. The gateway identifies the device from
     * the proof-authed [gatewayCredential]; the body carries only the token + platform.
     */
    suspend fun registerPushToken(
        scheme: String,
        host: String,
        gatewayCredential: String,
        token: String,
        platform: String = "android",
    )
}
