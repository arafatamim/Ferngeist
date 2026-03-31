package com.tamimarafat.ferngeist.feature.serverlist.helper

interface DesktopHelperRepository {
    suspend fun fetchStatus(scheme: String, host: String): DesktopHelperStatus

    suspend fun fetchAgents(scheme: String, host: String, helperCredential: String): List<DesktopHelperAgent>

    suspend fun startAgent(
        scheme: String,
        host: String,
        helperCredential: String,
        agentId: String,
    ): DesktopHelperRuntime

    suspend fun connectRuntime(
        scheme: String,
        host: String,
        helperCredential: String,
        runtimeId: String,
    ): DesktopHelperConnectResponse

    suspend fun fetchRuntimeLogs(
        scheme: String,
        host: String,
        helperCredential: String,
        runtimeId: String,
    ): List<DesktopHelperLogEntry>

    suspend fun startPairing(scheme: String, host: String): DesktopHelperPairingChallenge

    suspend fun completePairing(
        scheme: String,
        host: String,
        challengeId: String,
        code: String,
        deviceName: String,
    ): DesktopHelperPairingResult
}
