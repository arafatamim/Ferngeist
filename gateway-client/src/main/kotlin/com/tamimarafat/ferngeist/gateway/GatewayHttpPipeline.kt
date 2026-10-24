package com.tamimarafat.ferngeist.gateway

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import java.net.URLEncoder

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val NANOS_PER_MS = 1_000_000L

/**
 * Every JSON call the gateway repository makes funnels through here: [getJson],
 * [postJson], [postJsonUnit] and [deleteJsonUnit] share one signed-send path
 * ([sendSigned]) instead of repeating build headers → send → throw.
 *
 * Proof timestamps are signed against [GatewayClock], so a device whose clock is off
 * still lands inside the gateway's ±5-minute window, and the first `proof_expired`
 * rejection is recovered with a single re-signed retry.
 */
internal class GatewayHttpPipeline(
    httpClient: HttpClient,
    @PublishedApi
    internal val json: Json,
    private val clock: GatewayClock,
) {
    private val httpClient: HttpClient = httpClient

    /** Keep the response open while consuming NDJSON; ordinary get saves the whole body first. */
    internal suspend fun getStream(
        scheme: String,
        host: String,
        credential: String,
        agentIds: List<String>,
        consume: suspend (ByteReadChannel) -> Unit,
    ) {
        val endpoint =
            buildGatewayEndpoint(
                scheme,
                host,
                "v1",
                "projects",
                query =
                    mapOf(
                        "agents" to agentIds.joinToString(","),
                    ),
            )
        val request = GatewayProofRequest(method = "GET", endpoint = endpoint)
        repeat(2) { attempt ->
            val authHeaders = proofHeaders(host, credential, request)
            val failure =
                httpClient
                    .prepareGet {
                        url(endpoint)
                        accept(ContentType.parse("application/x-ndjson"))
                        timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                        authHeaders?.let { applyGatewayAuthHeaders(it) }
                    }.execute { response ->
                        if (response.status.isSuccess()) {
                            consume(response.bodyAsChannel())
                            null
                        } else {
                            streamFailure(host, endpoint, response)
                        }
                    }
            if (failure == null) return
            val rejection = parseGatewayErrorBody(failure.responseBody.orEmpty().trim())
            if (attempt > 0 ||
                failure.statusCode != HTTP_UNAUTHORIZED ||
                !rejection.isTransientProofRejection(failure.responseBody.orEmpty())
            ) {
                throw failure
            }
        }
    }

    private suspend fun streamFailure(
        host: String,
        endpoint: String,
        response: HttpResponse,
    ): GatewayRequestException {
        val body = response.bodyAsText()
        parseGatewayErrorBody(body.trim())?.let { recordServerClock(host, response, it) }
        return gatewayRequestException(response.status.value, response.status.description, endpoint, body)
    }

    internal suspend inline fun <reified T> getJson(
        scheme: String,
        host: String,
        credential: String? = null,
        vararg segments: String,
        query: Map<String, String> = emptyMap(),
    ): T = json.decodeFromString(getResponse(scheme, host, credential, segments, query).bodyAsText())

    internal suspend inline fun <reified T> postJson(
        scheme: String,
        host: String,
        credential: String? = null,
        vararg segments: String,
        body: String? = null,
    ): T = json.decodeFromString(postResponse(scheme, host, credential, body, segments).bodyAsText())

    internal suspend fun postJsonUnit(
        scheme: String,
        host: String,
        credential: String? = null,
        vararg segments: String,
        body: String? = null,
    ) {
        postResponse(scheme, host, credential, body, segments)
    }

    internal suspend fun deleteJsonUnit(
        scheme: String,
        host: String,
        credential: String? = null,
        vararg segments: String,
        query: Map<String, String> = emptyMap(),
    ) {
        val endpoint = buildGatewayEndpoint(scheme, host, segments = segments, query = query)
        sendSigned(
            request = GatewayProofRequest(method = "DELETE", endpoint = endpoint),
            host = host,
            credential = credential,
        ) { authHeaders ->
            httpClient.delete {
                url(endpoint)
                accept(ContentType.Application.Json)
                authHeaders?.let { applyGatewayAuthHeaders(it) }
            }
        }
    }

    internal suspend fun getResponse(
        scheme: String,
        host: String,
        credential: String? = null,
        segments: Array<out String>,
        query: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val endpoint = buildGatewayEndpoint(scheme, host, segments = segments, query = query)
        return sendSigned(
            request = GatewayProofRequest(method = "GET", endpoint = endpoint),
            host = host,
            credential = credential,
        ) { authHeaders ->
            httpClient.get {
                url(endpoint)
                accept(ContentType.Application.Json)
                authHeaders?.let { applyGatewayAuthHeaders(it) }
            }
        }
    }

    internal suspend fun postResponse(
        scheme: String,
        host: String,
        credential: String? = null,
        body: String? = null,
        segments: Array<out String>,
    ): HttpResponse {
        val endpoint = buildGatewayEndpoint(scheme, host, segments = segments)
        return sendSigned(
            request = GatewayProofRequest(method = "POST", endpoint = endpoint, body = body),
            host = host,
            credential = credential,
        ) { authHeaders ->
            httpClient.post {
                url(endpoint)
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                authHeaders?.let { applyGatewayAuthHeaders(it) }
                if (body != null) {
                    setBody(body)
                }
            }
        }
    }

    /** Wall-clock to the response head of one call, under the tag the chat open's step timer uses. */
    private fun logTiming(
        method: String,
        endpoint: String,
        response: HttpResponse,
        startNanos: Long,
    ) {
        val millis = (System.nanoTime() - startNanos) / NANOS_PER_MS
        // Path only: the query can carry file paths and refs.
        val path = endpoint.substringBefore('?').substringAfter("://").substringAfter('/')
        runCatching { Log.i("FerngeistLoad", "rest $method /$path ${response.status.value} ${millis}ms") }
    }

    /**
     * Signs, sends, and on the first correctable proof rejection re-signs with a fresh
     * nonce and fresh timestamp and sends once more. Never resends the original
     * headers — the nonce or timestamp is exactly what was rejected.
     *
     * Skewed device clocks surface as `proof_expired` rejections, reproduced in the
     * field at a 37-minute skew. The rejection carries the gateway's clock
     * ([GatewayErrorResponse.serverTime], or the HTTP `Date` header for older
     * gateways), so [recordServerClock] learns the offset before the retry. Retrying
     * a POST/DELETE is safe here: the gateway rejects the proof in its auth gate,
     * before any handler runs, so the first attempt had no effect. Unauthenticated
     * calls (`credential = null`) never retry.
     */
    private suspend fun sendSigned(
        request: GatewayProofRequest,
        host: String,
        credential: String?,
        send: suspend (GatewayAuthHeaders?) -> HttpResponse,
    ): HttpResponse {
        val proofCredential = credential?.takeIf { it.isNotBlank() }
        val startNanos = System.nanoTime()
        var response = send(proofHeaders(host, proofCredential, request))
        logTiming(request.method, request.endpoint, response, startNanos)
        if (response.status.isSuccess()) {
            return response
        }
        val failure = response.bodyAsText()
        val rejection = parseGatewayErrorBody(failure.trim())
        rejection?.let { recordServerClock(host, response, it) }
        if (
            proofCredential != null &&
            response.status.value == HTTP_UNAUTHORIZED &&
            rejection.isTransientProofRejection(failure)
        ) {
            val retryNanos = System.nanoTime()
            response = send(proofHeaders(host, proofCredential, request))
            logTiming(request.method, request.endpoint, response, retryNanos)
            if (response.status.isSuccess()) {
                return response
            }
        }
        throw gatewayRequestException(
            statusCode = response.status.value,
            statusDescription = response.status.description,
            endpoint = request.endpoint,
            responseBody = response.bodyAsText(),
        )
    }

    /**
     * Proof headers for [credential], signed against [clock]'s corrected time; null
     * for unauthenticated calls. The nonce comes fresh every call because a replayed
     * one is rejected on its own.
     */
    private fun proofHeaders(
        host: String,
        credential: String?,
        request: GatewayProofRequest,
    ): GatewayAuthHeaders? =
        credential?.let {
            GatewayProofAuth.buildAuthHeaders(
                gatewayCredential = it,
                request = request,
                timestampSeconds = clock.nowSeconds(host),
            )
        }

    /**
     * Learns the clock from an auth rejection whenever it exposes one: the body's
     * `serverTime`, or — for gateways that predate that field — the HTTP `Date`
     * header every gateway response carries. Guarded to auth rejections so unrelated
     * failures don't feed the offset.
     */
    private fun recordServerClock(
        host: String,
        response: HttpResponse,
        rejection: GatewayErrorResponse,
    ) {
        if (response.status.value != HTTP_UNAUTHORIZED && response.status.value != HTTP_FORBIDDEN) {
            return
        }
        val serverSeconds =
            parseGatewayServerTimeEpochSeconds(rejection.serverTime)
                ?: parseGatewayHttpDateEpochSeconds(response.headers[HttpHeaders.Date])
        serverSeconds?.let { clock.recordServerTime(host, it) }
    }

    internal fun buildGatewayEndpoint(
        scheme: String,
        host: String,
        vararg segments: String,
        query: Map<String, String> = emptyMap(),
    ): String {
        val normalizedScheme = normalizeControlScheme(scheme)
        val path = segments.joinToString("/")
        val base = "$normalizedScheme://${normalizeGatewayHost(host)}/$path"
        if (query.isEmpty()) return base
        val encodedQuery =
            query.entries.joinToString("&") { (key, value) ->
                val encodedKey = URLEncoder.encode(key, Charsets.UTF_8.name())
                val encodedValue = URLEncoder.encode(value, Charsets.UTF_8.name())
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
}

private fun io.ktor.client.request.HttpRequestBuilder.applyGatewayAuthHeaders(headers: GatewayAuthHeaders) {
    header("Authorization", headers.authorization)
    headers.proofTimestamp?.let { header("X-Ferngeist-Proof-Timestamp", it) }
    headers.proofNonce?.let { header("X-Ferngeist-Proof-Nonce", it) }
    headers.proofSignature?.let { header("X-Ferngeist-Proof-Signature", it) }
}
