package com.tamimarafat.ferngeist.gateway

import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.ToolCallContent
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class GatewayRepositoryImpl
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
    ) : GatewayRepository {
        override suspend fun fetchStatus(
            scheme: String,
            host: String,
        ): GatewayStatus {
            val response =
                httpClient.getJson<GatewayStatus>(
                    json = json,
                    scheme = scheme,
                    host = host,
                    bearerToken = null,
                    "v1",
                    "status",
                )
            return response
        }

        override suspend fun startPairing(
            scheme: String,
            host: String,
        ): GatewayPairStartResponse =
            httpClient.postJson(
                json = json,
                scheme = scheme,
                host = host,
                bearerToken = null,
                "v1",
                "pair",
                "start",
            )

        override suspend fun getPairingStatus(
            scheme: String,
            host: String,
            challengeId: String,
        ): GatewayPairStatusResponse =
            httpClient.getJson(
                json = json,
                scheme = scheme,
                host = host,
                bearerToken = null,
                "v1",
                "pair",
                "status",
                challengeId,
            )

        override suspend fun fetchAgents(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): List<GatewayAgent> {
            val response =
                httpClient.getJson<GatewayAgentsResponse>(
                    json,
                    scheme,
                    host,
                    gatewayCredential,
                    "v1",
                    "agents",
                )
            return response.agents
        }

        override suspend fun startAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ): GatewayRuntime {
            val response =
                httpClient.postJson<GatewayStartAgentResponse>(
                    json = json,
                    scheme = scheme,
                    host = host,
                    bearerToken = gatewayCredential,
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
            gatewayCredential: String,
            runtimeId: String,
            sessionMode: String?,
        ): GatewayConnectResponse =
            httpClient.postJson(
                json = json,
                scheme = scheme,
                host = host,
                bearerToken = gatewayCredential,
                "v1",
                "runtimes",
                runtimeId,
                "connect",
                body = sessionMode?.let {
                    json.encodeToString(GatewayConnectRequest(sessionMode = it))
                },
            )

        override suspend fun resumeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ): GatewaySessionResumeResponse =
            httpClient.postJson(
                json = json,
                scheme = scheme,
                host = host,
                bearerToken = gatewayCredential,
                "v1",
                "sessions",
                sessionId,
                "resume",
            )

        override suspend fun listGatewaySessions(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): List<GatewaySessionSummary> {
            val response =
                httpClient.getJson<GatewaySessionListResponse>(
                    json = json,
                    scheme = scheme,
                    host = host,
                    bearerToken = gatewayCredential,
                    "v1",
                    "sessions",
                )
            return response.sessions
        }

        override suspend fun closeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) {
            httpClient.deleteJsonUnit(
                json = json,
                scheme = scheme,
                host = host,
                bearerToken = gatewayCredential,
                "v1",
                "sessions",
                sessionId,
            )
        }

        override suspend fun registerPushToken(
            scheme: String,
            host: String,
            gatewayCredential: String,
            token: String,
            platform: String,
        ) {
            httpClient.postJsonUnit(
                json = json,
                scheme = scheme,
                host = host,
                bearerToken = gatewayCredential,
                "v1",
                "devices",
                "push-token",
                body = json.encodeToString(GatewayPushTokenRequest(token = token, platform = platform)),
            )
        }

        override suspend fun restartRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            envVars: Map<String, String>,
        ): GatewayConnectResponse =
            httpClient.postJson(
                json = json,
                scheme = scheme,
                host = host,
                bearerToken = gatewayCredential,
                "v1",
                "runtimes",
                runtimeId,
                "restart",
                body = json.encodeToString(GatewayRestartRequest(env = envVars)),
            )

        override suspend fun fetchRuntimeLogs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ): List<GatewayLogEntry> {
            val response =
                httpClient.getJson<GatewayRuntimeLogsResponse>(
                    json = json,
                    scheme = scheme,
                    host = host,
                    bearerToken = gatewayCredential,
                    "v1",
                    "runtimes",
                    runtimeId,
                    "logs",
                )
            return response.logs
        }

        override suspend fun fetchWorkspaceFile(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String,
        ): GatewayFileRead {
            val endpoint =
                buildGatewayEndpoint(
                    scheme,
                    host,
                    segments = arrayOf("v1", "runtimes", runtimeId, "files"),
                    query = mapOf("path" to path),
                )
            val authHeaders =
                GatewayProofAuth.buildAuthHeaders(
                    gatewayCredential = gatewayCredential,
                    method = "GET",
                    endpoint = endpoint,
                    body = null,
                )
            val response =
                httpClient.get {
                    url(endpoint)
                    accept(ContentType.Application.Json)
                    authHeaders.let { applyGatewayAuthHeaders(it) }
                }
            if (!response.status.isSuccess()) {
                throw gatewayRequestException(
                    response.status.value,
                    response.status.description,
                    endpoint,
                    response.bodyAsText(),
                )
            }
            val body = response.bodyAsText()
            val jsonElement = json.parseToJsonElement(body).jsonObject
            val size = jsonElement["size"]?.jsonPrimitive?.intOrNull ?: 0
            val truncated = jsonElement["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
            // The gateway returns the ACP TextResourceContents or BlobResourceContents
            // shape (plus size/truncated extensions). The two are distinguished by the
            // presence of `blob` (binary) vs `text`.
            return if (jsonElement["blob"] != null) {
                val contents = json.decodeFromJsonElement<EmbeddedResourceResource.BlobResourceContents>(jsonElement)
                GatewayFileRead.Binary(contents, size, truncated)
            } else {
                val contents = json.decodeFromJsonElement<EmbeddedResourceResource.TextResourceContents>(jsonElement)
                GatewayFileRead.Text(contents, size, truncated)
            }
        }

        override suspend fun fetchGitStatus(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ): GatewayGitStatus =
            httpClient.getJson(
                json = json,
                scheme = scheme,
                host = host,
                bearerToken = gatewayCredential,
                segments = arrayOf("v1", "runtimes", runtimeId, "git", "status"),
            )

        override suspend fun fetchGitDiff(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String?,
        ): List<ToolCallContent.Diff> {
            val segments = arrayOf("v1", "runtimes", runtimeId, "git", "diff")
            val query = path?.takeIf { it.isNotBlank() }?.let { mapOf("path" to it) } ?: emptyMap()
            return if (query.isEmpty()) {
                // Whole-tree: gateway returns a JSON array of ToolCallContentDiff objects.
                httpClient.getJson(
                    json = json,
                    scheme = scheme,
                    host = host,
                    bearerToken = gatewayCredential,
                    segments = segments,
                )
            } else {
                // Single file: gateway returns one ToolCallContentDiff object.
                val single =
                    httpClient.getJson<ToolCallContent.Diff>(
                        json = json,
                        scheme = scheme,
                        host = host,
                        bearerToken = gatewayCredential,
                        segments = segments,
                        query = query,
                    )
                listOf(single)
            }
        }

        override suspend fun completePairing(
            scheme: String,
            host: String,
            challengeId: String,
            code: String,
            deviceName: String,
        ): GatewayPairingResult {
            val proofKey = GatewayProofAuth.generateProofKey()
            val response =
                httpClient.postJson<GatewayPairCompleteResponse>(
                    json = json,
                    scheme = scheme,
                    host = host,
                    bearerToken = null,
                    "v1",
                    "pair",
                    "complete",
                    body =
                        json.encodeToString(
                            GatewayPairCompleteRequest(
                                challengeId = challengeId,
                                code = code,
                                deviceName = deviceName,
                                proofPublicKey = proofKey.publicKey,
                            ),
                        ),
                )
            return GatewayPairingResult(
                deviceId = response.deviceId,
                deviceName = response.deviceName,
                gatewayCredential = GatewayProofAuth.encodeStoredCredential(response.token, proofKey.privateKey),
                expiresAt = response.expiresAt,
                gatewayId = response.gatewayId,
            )
        }

        override suspend fun refreshCredential(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): GatewayPairingResult {
            val response =
                httpClient.postJson<GatewayPairCompleteResponse>(
                    json = json,
                    scheme = scheme,
                    host = host,
                    bearerToken = gatewayCredential,
                    "v1",
                    "auth",
                    "refresh",
                )
            return GatewayPairingResult(
                deviceId = response.deviceId,
                deviceName = response.deviceName,
                gatewayCredential = GatewayProofAuth.rotateStoredCredential(gatewayCredential, response.token),
                expiresAt = response.expiresAt,
                gatewayId = response.gatewayId,
            )
        }
    }

private suspend inline fun <reified T> HttpClient.getJson(
    json: Json,
    scheme: String,
    host: String,
    bearerToken: String? = null,
    vararg segments: String,
    query: Map<String, String> = emptyMap(),
): T {
    val endpoint = buildGatewayEndpoint(scheme, host, segments = segments, query = query)
    val authHeaders =
        bearerToken?.takeIf { it.isNotBlank() }?.let {
            GatewayProofAuth.buildAuthHeaders(
                gatewayCredential = it,
                method = "GET",
                endpoint = endpoint,
                body = null,
            )
        }
    val response =
        get {
            url(endpoint)
            accept(ContentType.Application.Json)
            authHeaders?.let { applyGatewayAuthHeaders(it) }
        }
    if (!response.status.isSuccess()) {
        throw gatewayRequestException(
            response.status.value,
            response.status.description,
            endpoint,
            response.bodyAsText(),
        )
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
    val endpoint = buildGatewayEndpoint(scheme, host, *segments)
    val authHeaders =
        bearerToken?.takeIf { it.isNotBlank() }?.let {
            GatewayProofAuth.buildAuthHeaders(
                gatewayCredential = it,
                method = "POST",
                endpoint = endpoint,
                body = body,
            )
        }
    val response =
        post {
            url(endpoint)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            authHeaders?.let { applyGatewayAuthHeaders(it) }
            if (body != null) {
                setBody(body)
            }
        }
    if (!response.status.isSuccess()) {
        throw gatewayRequestException(
            response.status.value,
            response.status.description,
            endpoint,
            response.bodyAsText(),
        )
    }
    return json.decodeFromString(response.bodyAsText())
}

private suspend fun HttpClient.postJsonUnit(
    json: Json,
    scheme: String,
    host: String,
    bearerToken: String? = null,
    vararg segments: String,
    body: String? = null,
) {
    val endpoint = buildGatewayEndpoint(scheme, host, *segments)
    val authHeaders =
        bearerToken?.takeIf { it.isNotBlank() }?.let {
            GatewayProofAuth.buildAuthHeaders(
                gatewayCredential = it,
                method = "POST",
                endpoint = endpoint,
                body = body,
            )
        }
    val response =
        post {
            url(endpoint)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            authHeaders?.let { applyGatewayAuthHeaders(it) }
            if (body != null) {
                setBody(body)
            }
        }
    if (!response.status.isSuccess()) {
        throw gatewayRequestException(
            response.status.value,
            response.status.description,
            endpoint,
            response.bodyAsText(),
        )
    }
}

private suspend fun HttpClient.deleteJsonUnit(
    json: Json,
    scheme: String,
    host: String,
    bearerToken: String? = null,
    vararg segments: String,
) {
    val endpoint = buildGatewayEndpoint(scheme, host, *segments)
    val authHeaders =
        bearerToken?.takeIf { it.isNotBlank() }?.let {
            GatewayProofAuth.buildAuthHeaders(
                gatewayCredential = it,
                method = "DELETE",
                endpoint = endpoint,
                body = null,
            )
        }
    val response =
        delete {
            url(endpoint)
            accept(ContentType.Application.Json)
            authHeaders?.let { applyGatewayAuthHeaders(it) }
        }
    if (!response.status.isSuccess()) {
        throw gatewayRequestException(
            response.status.value,
            response.status.description,
            endpoint,
            response.bodyAsText(),
        )
    }
}

private fun buildGatewayEndpoint(
    scheme: String,
    host: String,
    vararg segments: String,
    query: Map<String, String> = emptyMap(),
): String {
    val normalizedScheme = normalizeControlScheme(scheme)
    val normalizedHost = normalizeGatewayHost(host)
    val path = segments.joinToString("/")
    val base = "$normalizedScheme://$normalizedHost/$path"
    if (query.isEmpty()) return base
    val encodedQuery =
        query.entries.joinToString("&") { (key, value) ->
            val encodedKey = java.net.URLEncoder.encode(key, Charsets.UTF_8.name())
            val encodedValue = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
            "$encodedKey=$encodedValue"
        }
    return "$base?$encodedQuery"
}

private fun normalizeControlScheme(scheme: String): String =
    when (scheme.trim().lowercase()) {
        "", "http", "ws" -> "http"
        "https", "wss" -> "https"
        else -> scheme.trim().lowercase()
    }

private fun normalizeGatewayHost(host: String): String =
    host
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("ws://")
        .removePrefix("wss://")
        .trimEnd('/')

private fun gatewayRequestException(
    statusCode: Int,
    statusDescription: String,
    endpoint: String,
    responseBody: String,
): IllegalStateException {
    val normalizedBody = responseBody.trim()
    val statusLine = "$statusCode ${statusDescription.ifBlank { "unknown" }}".trim()
    val message =
        when (statusCode) {
            404 -> {
                buildString {
                    append("Gateway request failed: ")
                    append(statusLine)
                    append(" at ")
                    append(endpoint)
                    append(". The host is reachable, but this port is not serving the Ferngeist Gateway API.")
                    if (normalizedBody.isNotBlank()) {
                        append(" Response: ")
                        append(normalizedBody)
                    }
                }
            }
            else -> {
                buildString {
                    append("Gateway request failed: ")
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

private fun io.ktor.client.request.HttpRequestBuilder.applyGatewayAuthHeaders(headers: GatewayAuthHeaders) {
    header("Authorization", headers.authorization)
    headers.proofTimestamp?.let { header("X-Ferngeist-Proof-Timestamp", it) }
    headers.proofNonce?.let { header("X-Ferngeist-Proof-Nonce", it) }
    headers.proofSignature?.let { header("X-Ferngeist-Proof-Signature", it) }
}
