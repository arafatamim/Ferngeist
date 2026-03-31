package com.tamimarafat.ferngeist.feature.serverlist.helper

import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.path
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Small helper-control client used by the app setup flow. It intentionally
 * covers only status and pairing for now; runtime/agent APIs come later.
 */
class DesktopHelperRepositoryImpl @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) : DesktopHelperRepository {

    override suspend fun fetchStatus(scheme: String, host: String): DesktopHelperStatus {
        val response = httpClient.getJson<DesktopHelperStatus>(json, scheme, host, "v1", "status")
        return response
    }

    override suspend fun fetchAgents(scheme: String, host: String, helperCredential: String): List<DesktopHelperAgent> {
        val response = httpClient.getJson<DesktopHelperAgentsResponse>(
            json,
            scheme,
            host,
            helperCredential,
            "v1",
            "agents",
        )
        return response.agents
    }

    override suspend fun startAgent(
        scheme: String,
        host: String,
        helperCredential: String,
        agentId: String,
    ): DesktopHelperRuntime {
        val response = httpClient.postJson<DesktopHelperStartAgentResponse>(
            json = json,
            scheme = scheme,
            host = host,
            bearerToken = helperCredential,
            "v1",
            "agents",
            agentId,
            "start",
        )
        return response.runtime
    }

    override suspend fun connectRuntime(
        scheme: String,
        host: String,
        helperCredential: String,
        runtimeId: String,
    ): DesktopHelperConnectResponse {
        return httpClient.postJson(
            json = json,
            scheme = scheme,
            host = host,
            bearerToken = helperCredential,
            "v1",
            "runtimes",
            runtimeId,
            "connect",
        )
    }

    override suspend fun restartRuntime(
        scheme: String,
        host: String,
        helperCredential: String,
        runtimeId: String,
        envVars: Map<String, String>,
    ): DesktopHelperConnectResponse {
        return httpClient.postJson(
            json = json,
            scheme = scheme,
            host = host,
            bearerToken = helperCredential,
            "v1",
            "runtimes",
            runtimeId,
            "restart",
            body = json.encodeToString(DesktopHelperRestartRequest(env = envVars)),
        )
    }

    override suspend fun fetchRuntimeLogs(
        scheme: String,
        host: String,
        helperCredential: String,
        runtimeId: String,
    ): List<DesktopHelperLogEntry> {
        val response = httpClient.getJson<DesktopHelperRuntimeLogsResponse>(
            json = json,
            scheme = scheme,
            host = host,
            bearerToken = helperCredential,
            "v1",
            "runtimes",
            runtimeId,
            "logs",
        )
        return response.logs
    }

    override suspend fun startPairing(scheme: String, host: String): DesktopHelperPairingChallenge {
        val response = httpClient.postJson<DesktopHelperPairStartResponse>(json, scheme, host, "v1", "pair", "start")
        return DesktopHelperPairingChallenge(
            challengeId = response.challengeId,
            code = response.code,
            expiresAt = response.expiresAt,
        )
    }

    override suspend fun completePairing(
        scheme: String,
        host: String,
        challengeId: String,
        code: String,
        deviceName: String,
    ): DesktopHelperPairingResult {
        val response = httpClient.postJson<DesktopHelperPairCompleteResponse>(
            json = json,
            scheme = scheme,
            host = host,
            "v1",
            "pair",
            "complete",
            body = json.encodeToString(
                DesktopHelperPairCompleteRequest(
                    challengeId = challengeId,
                    code = code,
                    deviceName = deviceName,
                ),
            ),
        )
        return DesktopHelperPairingResult(
            deviceId = response.deviceId,
            deviceName = response.deviceName,
            token = response.token,
            expiresAt = response.expiresAt,
        )
    }
}

private suspend inline fun <reified T> HttpClient.getJson(
    json: Json,
    scheme: String,
    host: String,
    bearerToken: String? = null,
    vararg segments: String,
): T {
    val response = get {
        url {
            protocol = io.ktor.http.URLProtocol.createOrDefault(scheme)
            this.host = host.substringBefore(':')
            host.substringAfter(':', "").toIntOrNull()?.let { port = it }
            path(*segments)
        }
        accept(ContentType.Application.Json)
        bearerToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }
    check(response.status.isSuccess()) { "Helper request failed: ${response.status}" }
    return json.decodeFromString(response.bodyAsText())
}

private suspend inline fun <reified T> HttpClient.postJson(
    json: Json,
    scheme: String,
    host: String,
    bearerToken: String? = null,
    vararg segments: String,
    body: String? = null,
): T {
    val response = post {
        url {
            protocol = io.ktor.http.URLProtocol.createOrDefault(scheme)
            this.host = host.substringBefore(':')
            host.substringAfter(':', "").toIntOrNull()?.let { port = it }
            path(*segments)
        }
        accept(ContentType.Application.Json)
        contentType(ContentType.Application.Json)
        bearerToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        if (body != null) {
            setBody(body)
        }
    }
    check(response.status.isSuccess()) { "Helper request failed: ${response.status}" }
    return json.decodeFromString(response.bodyAsText())
}
