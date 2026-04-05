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
        val response = httpClient.getJson<DesktopHelperStatus>(
            json = json,
            scheme = scheme,
            host = host,
            bearerToken = null,
            "v1",
            "status",
        )
        return response
    }

    override suspend fun startPairing(scheme: String, host: String): DesktopHelperPairStartResponse {
        return httpClient.postJson(
            json = json,
            scheme = scheme,
            host = host,
            bearerToken = null,
            "v1",
            "pair",
            "start",
        )
    }

    override suspend fun getPairingStatus(scheme: String, host: String, challengeId: String): DesktopHelperPairStatusResponse {
        return httpClient.getJson(
            json = json,
            scheme = scheme,
            host = host,
            bearerToken = null,
            "v1",
            "pair",
            "status",
            challengeId,
        )
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

    override suspend fun completePairing(
        scheme: String,
        host: String,
        challengeId: String,
        code: String,
        deviceName: String,
    ): DesktopHelperPairingResult {
        val proofKey = DesktopHelperProofAuth.generateProofKey()
        val response = httpClient.postJson<DesktopHelperPairCompleteResponse>(
            json = json,
            scheme = scheme,
            host = host,
            bearerToken = null,
            "v1",
            "pair",
            "complete",
            body = json.encodeToString(
                DesktopHelperPairCompleteRequest(
                    challengeId = challengeId,
                    code = code,
                    deviceName = deviceName,
                    proofPublicKey = proofKey.publicKey,
                ),
            ),
        )
        return DesktopHelperPairingResult(
            deviceId = response.deviceId,
            deviceName = response.deviceName,
            helperCredential = DesktopHelperProofAuth.encodeStoredCredential(response.token, proofKey.privateKey),
            expiresAt = response.expiresAt,
        )
    }

    override suspend fun refreshCredential(
	    scheme: String,
	    host: String,
	    helperCredential: String,
	): DesktopHelperPairingResult {
	    val response = httpClient.postJson<DesktopHelperPairCompleteResponse>(
	        json = json,
	        scheme = scheme,
	        host = host,
	        bearerToken = helperCredential,
	        "v1",
	        "auth",
	        "refresh",
	    )
	    return DesktopHelperPairingResult(
	        deviceId = response.deviceId,
	        deviceName = response.deviceName,
	        helperCredential = DesktopHelperProofAuth.rotateStoredCredential(helperCredential, response.token),
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
    val endpoint = buildHelperEndpoint(scheme, host, *segments)
    val authHeaders = bearerToken?.takeIf { it.isNotBlank() }?.let {
        DesktopHelperProofAuth.buildAuthHeaders(
            helperCredential = it,
            method = "GET",
            endpoint = endpoint,
            body = null,
        )
    }
    val response = get {
        url(endpoint)
        accept(ContentType.Application.Json)
        authHeaders?.let { applyHelperAuthHeaders(it) }
    }
    if (!response.status.isSuccess()) {
        throw helperRequestException(response.status.value, response.status.description, endpoint, response.bodyAsText())
    }
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
    val endpoint = buildHelperEndpoint(scheme, host, *segments)
    val authHeaders = bearerToken?.takeIf { it.isNotBlank() }?.let {
        DesktopHelperProofAuth.buildAuthHeaders(
            helperCredential = it,
            method = "POST",
            endpoint = endpoint,
            body = body,
        )
    }
    val response = post {
        url(endpoint)
        accept(ContentType.Application.Json)
        contentType(ContentType.Application.Json)
        authHeaders?.let { applyHelperAuthHeaders(it) }
        if (body != null) {
            setBody(body)
        }
    }
    if (!response.status.isSuccess()) {
        throw helperRequestException(response.status.value, response.status.description, endpoint, response.bodyAsText())
    }
    return json.decodeFromString(response.bodyAsText())
}

private fun buildHelperEndpoint(scheme: String, host: String, vararg segments: String): String {
    val normalizedScheme = normalizeControlScheme(scheme)
    val normalizedHost = normalizeHelperHost(host)
    return "$normalizedScheme://$normalizedHost/${segments.joinToString("/")}"
}

private fun normalizeControlScheme(scheme: String): String {
    return when (scheme.trim().lowercase()) {
        "", "http", "ws" -> "http"
        "https", "wss" -> "https"
        else -> scheme.trim().lowercase()
    }
}

private fun normalizeHelperHost(host: String): String {
    return host.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("ws://")
        .removePrefix("wss://")
        .trimEnd('/')
}

private fun helperRequestException(statusCode: Int, statusDescription: String, endpoint: String, responseBody: String): IllegalStateException {
    val normalizedBody = responseBody.trim()
    val statusLine = "$statusCode ${statusDescription.ifBlank { "unknown" }}".trim()
    val message = when (statusCode) {
        404 -> {
            buildString {
                append("Desktop companion request failed: ")
                append(statusLine)
                append(" at ")
                append(endpoint)
                append(". The host is reachable, but this port is not serving the Ferngeist desktop companion API.")
                if (normalizedBody.isNotBlank()) {
                    append(" Response: ")
                    append(normalizedBody)
                }
            }
        }
        else -> {
            buildString {
                append("Desktop companion request failed: ")
                append(statusLine)
                append(" at ")
                append(endpoint)
                if (normalizedBody.isNotBlank()) {
                    append(". Response: ")
                    append(normalizedBody)
                }
            }
        }
    }
    return IllegalStateException(message)
}

private fun io.ktor.client.request.HttpRequestBuilder.applyHelperAuthHeaders(headers: DesktopHelperAuthHeaders) {
    header("Authorization", headers.authorization)
    headers.proofTimestamp?.let { header("X-Ferngeist-Proof-Timestamp", it) }
    headers.proofNonce?.let { header("X-Ferngeist-Proof-Nonce", it) }
    headers.proofSignature?.let { header("X-Ferngeist-Proof-Signature", it) }
}
