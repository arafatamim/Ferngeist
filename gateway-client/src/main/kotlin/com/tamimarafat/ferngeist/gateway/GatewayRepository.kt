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
}
